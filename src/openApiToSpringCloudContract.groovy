import java.lang.annotation.AnnotationFormatError
import groovy.json.JsonSlurper
import groovy.transform.*

@GrabConfig( )
@Grab('org.yaml:snakeyaml:1.17')
import org.yaml.snakeyaml.Yaml
class openApiToSpringCloudContract {
   static main(String... args){
	   if (args.length < 3) {
		   System.err.println("Usage: openApiToSpringCloudContract [openAPISpecFile.yaml] [outputDir] [contractType:rest]")
		   System.exit(1)
	   }
	   def fileName = args[0]
	   def outputDirName = args[1]
	   def contractType = args[2]
	   def endpointDir = null
	   if(args.length == 4) {
	   		endpointDir = args[3]
	   }
	   new OpenApi2SpringCloudContractGenerator().generateSpringCloudContractDSL(fileName,outputDirName, contractType, endpointDir)
   } 
}

class OpenApi2SpringCloudContractGenerator {
	
	def generateSpringCloudContractDSL(filename, outputDirName, contractType, endpointDir) {
		
		println "reading ${filename}..."
		Map openApiSpec = readFile(filename)
		
		def paths = openApiSpec.paths
		paths.each { path ->
						def consumers = collectConsumersNames(path)
						if (! isEndpointCompletlyIgnored(path.value.values())) {
							if(consumers.isEmpty()) {
								errorMissingConsumer(path.key, null)
							}
						} else {
							println("ignoring ${path.key}")
						}
						
						for (consumer in consumers) {
							def contract  = generateContractForPath(openApiSpec, path, consumer)
							if(contract == null) {
								// contract was ignored for each httpmethod
								return
							}

							def endpoint = "${path.key}"		
							def fileName = fileNameForEndpoint(outputDirName, endpoint, contractType, consumer, endpointDir)
			
							println "writing ${fileName} ..." 
							new File(fileName).withOutputStream { stream ->
								stream << contract
							}	
						}
			}
	}

	def readFile(String filename) {
		String fileType = determineFileType(filename)
		if(fileType == "YAML") {
			Yaml parser = new Yaml()	
			return parser.load((filename as File).text)
		} else if(fileType == "JSON") {
			JsonSlurper parser = new JsonSlurper()
			return parser.parseText((filename as File).text)
		} else {
			println "Unknown filetype: ${filename}."
			abort()
		}
	}

	def determineFileType(String filename) {
		def matcher = (filename =~ /.*\.(.*)$/)

		if(matcher.matches()) {
    		def extension = matcher[0][1]
    		if(extension.toLowerCase() in ["yml", "yaml"]) {
    			return "YAML"
    		} else if(extension.toLowerCase() == "json") {
    			return "JSON"
    		}
    		return "UNKNOWN"
    	}
    }
	
    /*
     * Generate Contract DSL for each specified path	
     */
	def generateContractForPath(openApiSpec, path, consumer) {
		def endpoint = "${path.key}"
		def httpMethods = path.value.keySet()

		if (isEndpointCompletlyIgnored(path.value.values())) {
			return null
		}

		def contract = """
import org.springframework.cloud.contract.spec.Contract

[       """
		for (def i = 0; i < httpMethods.size(); i++) {
			def httpMethod = httpMethods[i];
			def pathSpec = path.value[httpMethod]

			if(! extractConsumers(pathSpec,endpoint, httpMethod).contains(consumer)) {
				continue
			}

			def responseStatusCode = findAnySuccessfullStatusCode(pathSpec.responses);
		 	def injectedEndpointURL = injectParamsIntoEndpoint(
		 		endpoint, pathSpec, openApiSpec, pathSpec.responses[responseStatusCode]?.schema
		 	);

		 	def additionalHeaders = collectHeaders(pathSpec.parameters)

		 	contract += """
	Contract.make {
    	request {
       		description(\"\"\"
				${pathSpec.description}

            	URL: ${httpMethod.toUpperCase()} to ${endpoint}
			
        	\"\"\")
        	method \'${httpMethod.toUpperCase()}\'
        	url \'${injectedEndpointURL}\'\n"""
		
			def requestBodySchema = pathSpec.parameters.find{it.in == 'body'}?.schema
		
			if (requestBodySchema) {	
		  		contract = contract + """ 
        	body (\"\"\"\n
${generateSampleJsonForBody(openApiSpec.definitions, requestBodySchema)}
            	\n\"\"\")
        		"""
			}
			contract = contract + """ 
       		headers {
          		header('Content-Type', 'application/json')
          		header('Accept','application/json')
          		${additionalHeaders}
       		}
   		}
      
   		response {
       		status ${responseStatusCode} """
			def responseBodySchema = pathSpec.responses[responseStatusCode]?.schema
			if (responseBodySchema) {
		  		contract = contract + """ 
        	body (\"\"\"\n
${generateSampleJsonForBody(openApiSpec.definitions, responseBodySchema)}
            \n\"\"\")
        	"""
        	/*
        	headers {
          		header('Content-Type', 'application/json')
        	}
        	*/
			}
			contract = contract + """		  
   		}
	}"""	
        	if (i < httpMethods.size() - 1) {
				contract += ",\n";
			}
		}
	   contract += """
]
""";
	   	return contract
	}

	def errorMissingConsumer(endpoint, method) {
		String methodPath = (method != null ? method.toUpperCase() + ":" : "") + endpoint
		println "\n\nError: No consumers found for (unignored) endpoint '${methodPath}'."
		println "Specify them with the 'x-consumers' (list) attribute or exclude the endpoint with the 'x-ignored: true' attribute."
		abort()
	}

	def abort() {
		println "Aborting execution."
		System.exit(0)
	}

	def collectConsumersNames(path) {
		path.value.keySet()
		.findAll {
			! isIgnored(path.value[it])
		}
		.findAll{
			path.value[it]['x-consumers'] != null
		}.collectMany {
			path.value[it]['x-consumers']
		} as Set
	}

	def extractConsumers(pathSpec, endpoint, httpmethod) {
		if(isIgnored(pathSpec)) {
			return []
		}

		if(! pathSpec.containsKey('x-consumers')) {
			errorMissingConsumer(endpoint, httpmethod)
		}

		return pathSpec['x-consumers']
	}

	def isIgnored(pathSpec) {
		return pathSpec.containsKey('x-ignored') && pathSpec['x-ignored'] == true
	}

	def isEndpointCompletlyIgnored(endpointValues) {
		return endpointValues.findAll { ! isIgnored(it) }.isEmpty()
	}
	
	/*
	 * Generate a sample JSON document for a Request or Response body
	 */
	def generateSampleJsonForBody(schemaDefinitions, schema) {
		
		def schemaType = (schema.type == 'array') ? schema.items : schema 
		
		if (schemaType['$ref']){
		   def bodyType = schemaTypeFromRef(schemaType)
		   def builder = new groovy.json.JsonBuilder()
		   def content = [schemaToJsonExample(builder, schemaDefinitions,bodyType)]
		   return (schema.type == 'array') ?
			 new groovy.json.JsonBuilder(content).toPrettyString() : builder.toPrettyString()
		} else if (schema.type == 'string') {
			return '"' + sampleValueForSimpleTypeField("body",schemaType) + '"'
		} else if (schema.type == 'object') {
			// generic type 'Object' can not be interpreted.
			return "{ }"
		} else {
			return sampleValueForSimpleTypeField("body",schemaType)
		}
		
		
	}
	
	/*
	 * Substitute sample values for path variable placeholders
	 */
	def injectParamsIntoEndpoint(endpoint, pathSpec, openApiSpec, responseSchemaOptional) {
		def pathParams = (pathSpec.parameters as List).findAll{it.in == 'path'}
		
		def tokens = endpoint.split('/')

		def endpointWithInjectVariables = tokens.collect { tok ->
			if (tok.startsWith('{') && tok.endsWith('}')) {
				def variableName = tok.replaceAll("\\?\\w+", "").replaceAll("[\\[\\](){}]","")
				def pathParam = pathParams.find{it.name == variableName}
				if (!pathParam) {
					throw new RuntimeException("path variable '$variableName' in endpoint path $endpoint not declared as a path parameter")
				}
				
				if (pathParam['x-example']) {
					return pathParam['x-example']
				}
				if (pathParam.type=='string') {
					return "some${variableName.capitalize()}"
				}
				else if (pathParam.type=='number' || pathParam.type == 'integer') {
					return 0
				}
			}
			return tok
		}
		
		def resourcePath =  endpointWithInjectVariables.join('/').replaceAll("(\\{\\?\\w+\\})", "")
		
		def queryParams = (pathSpec.parameters as List).findAll{it.in == 'query'}
		
		queryParams.eachWithIndex {qp, i ->
			 
			def delim = (i == 0) ? '?' : '&'			
			def name = qp.name 
			def val
			if (qp.type == 'array') { 
				val = sampleValueForSimpleTypeField(name, qp.items)
				if (qp.collectionFormat == 'multi') {
					name = name + '[]'
					resourcePath = "${resourcePath}${delim}${name}=${val}1&${name}=${val}2"
				}
				else {
				    println "WARNING: collectionFormat ${qp.collectionFormat} not supported"
				}
			}
			else {
			   val = sampleValueForSimpleTypeField(name, qp)
			   resourcePath = "${resourcePath}${delim}${name}=${val}"
			}
			
			
		}
		
		return resourcePath
	}

	def collectHeaders(parameters) {
		def headerParams = (parameters as List).findAll{it.in == 'header'}


		def headerStrings = []

		for(header in headerParams) {
			if (header.required) {
				headerStrings << "header('${header.name}','${sampleValueForSimpleTypeField(header.name, header)}')"
			}
		}

		return headerStrings.join('\n')
	}
	
	/*
	 * Generate a fileName for the endpoint
	 */
	def fileNameForEndpoint(outputDirName, endpoint, contractType, consumer, resourceDirName) {
		def tokens = endpoint.split('/')

		if(resourceDirName == null) {
			resourceDirName = tokens[1];
			if(tokens.size() > 2) {
				resourceDirName = tokens[2];
			}
		}
		def caps = tokens.findAll{!it.empty}.collect { tok-> 
		def cap = tok.replaceAll("[\\[\\](){}]","") as CharSequence
			cap.capitalize()
		}
		
		def resourcePath = "${outputDirName}/${consumer}/${contractType}/${resourceDirName}"
		def resourceFilePath = new File(resourcePath)
		if(! resourceFilePath.exists()) {
			resourceFilePath.mkdirs()
		}

		return "${resourcePath}/${caps.join('')}ContractTest.groovy"
	}
	
	/*
	 * Recursively generate sample JSON document for a declared schema type
	 */
	def schemaToJsonExample(builder, schemaDefinitions, type) {
		 
		def schemaProperties = schemaDefinitions["${type}"].properties
		
		def result = builder  { 
			schemaProperties.each {name,schema ->
				if (schema.example != null){
					"$name"  schema.example
				 } else if (schema.type  && schema.type != 'array') {
					 "$name"  sampleValueForSimpleTypeField(name, schema)		
				 } else if (schema.type == 'array') {
					 def array = []
					 if (schema.example != null) {
					   "$name" schema.example
					 } else if (schema.items['$ref']){
					   "$name" array << schemaToJsonExample(builder, schemaDefinitions, schemaTypeFromRef(schema.items))
					 } 
					 else {
					    "$name" array << sampleValueForSimpleTypeField(name, schema.items)							
                     }					 
				 } 
				 else if (schema['$ref']) { 
					  
					"$name"  schemaToJsonExample(builder, schemaDefinitions, schemaTypeFromRef(schema))
				 }
				 
			}
		 
		}
		
		return result
		
	}
	
	/*
	 * Parse the schema type name from the $ref attribute
	 */
	def schemaTypeFromRef(schema) {
	     
		def type = (schema['$ref'] =~ /#\/definitions\/(.+)/)[0][1]
		 
		return type
	}
	
	/*
	 * Generate a sample value for a simple type field
	 */
	def sampleValueForSimpleTypeField(name, property) {

		if (property.example){
			return property.example
		}

		if (property.containsKey('x-example')){
			return property['x-example']
		}

		if (property.containsKey('default')){
			return property['default']
		}

		def type = property.type
		def format = property.format
		
		if (type == 'string') {
			return applyFormatToStringField(format, name)
		}
		else if (type == 'number' || type == 'integer') {
			return 0
		}
		else if (type == 'boolean') {
		    return true
		}
	}
	
	/*
	 * Apply format to string field
	 */
	def applyFormatToStringField(format,name) {
		if (format == 'date-time'){
			return new Date().format("yyyy-MM-dd'T'hh:mm:ss.S")
			//return "2017-01-01T00:00:00.0"
		}
		else if (format == 'date') {
			return new Date().format('yyyy-MM-dd')
			return "2017-01-01"
		}
		return "some${name.capitalize()}"
	}

	def findAnySuccessfullStatusCode(responseSpec) {
		for(status in responseSpec) {
			def code = status.key.toInteger();
			if(code >= 200 && code < 300) {
				return status.key;
			}
		}
		return '200';
	}
}


