import java.lang.annotation.AnnotationFormatError

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
	   new OpenApi2SpringCloudContractGenerator().generateSpringCloudContractDSL(fileName,outputDirName, contractType)
   } 
}

class OpenApi2SpringCloudContractGenerator {
	
	def generateSpringCloudContractDSL(filename, outputDirName, contractType) {
		
		println "reading ${filename}..."
		
		Yaml parser = new Yaml()
		Map openApiSpec = parser.load((filename as File).text)
		
		def paths = openApiSpec.paths
		paths.each { path ->
						def consumers = collectConsumersNames(path)
						if (consumers.isEmpty()) {
							println "No consumers found for (unignored) endpoint ${path.key}!"
							println "Specify them with the 'x-consumers' (list) attribute or exclude the endpoint with the 'x-ignored: true' attribute."
							abort()
						}
						
						for (consumer in consumers) {
							def contract  = generateContractForPath(openApiSpec, path, consumer)
							if(contract == null) {
								// contract was ignored for each httpmethod
								return
							}
							def endpoint = "${path.key}"		

							def fileName = fileNameForEndpoint(outputDirName,endpoint, contractType, consumer)
			
							println "writing ${fileName} ..." 
							new File(fileName).withOutputStream { stream ->
								stream << contract
							}	
						}
			}
	}

	def abort() {
		println "Aborting execution."
		System.exit(0)
	}

	def collectConsumersNames(path) {
		path.value.keySet()
		.findAll {
			! path.value[it].containsKey('x-ignored') || path.value[it]['x-ignored'] == false
		}.findAll{
			path.value[it]['x-consumers'] != null
		}.collectMany {
			path.value[it]['x-consumers']
		} as Set
	}
	
    /*
     * Generate Contract DSL for each specified path	
     */
	def generateContractForPath(openApiSpec, path, consumer) {
		def endpoint = "${path.key}"
		def httpMethods = path.value.keySet()
		println(httpMethods)
		def ignoredHttpMethods = 0
		def contract = """
import org.springframework.cloud.contract.spec.Contract

[       """
		for (def i = 0; i < httpMethods.size(); i++) {
			def httpMethod = httpMethods[i];
			def pathSpec = path.value[httpMethod]
			if(! pathSpec['x-consumers'].contains(consumer) 
				|| ( pathSpec.containsKey('x-ignored') && pathSpec['x-ignored'] == true)) {
				ignoredHttpMethods++
				continue
			}
			def responseStatusCode = findAnySuccessfullStatusCode(pathSpec.responses);
		 	def injectedEndpointURL = injectParamsIntoEndpoint(
		 		endpoint, pathSpec, openApiSpec, pathSpec.responses[responseStatusCode]?.schema
		 	);

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
	   	if(httpMethods.size() == ignoredHttpMethods) {
	   		return null
	   	} else {
	   		return contract
		}
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
	
	/*
	 * Generate a fileName for the endpoint
	 */
	def fileNameForEndpoint(outputDirName, endpoint, contractType, consumer) {
		def tokens = endpoint.split('/')

		def resourceDirName = tokens[1];
		if(tokens.size() > 2) {
			resourceDirName = tokens[2];
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
			println(name + ", " + property.example)
			return property.example
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


