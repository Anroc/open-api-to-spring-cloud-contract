
package contracts.transactions

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    request {
       description("""
Multiple status values can be provided with comma seperated strings

            Given:
                GET to /v2/pets/findByStatus
            When:
				
            And:
				
            Then:
			
        """)
        method 'GET'
        url '/v2/pets/findByStatus?status[]=someStatus1&status[]=someStatus2'
 
       headers {
          header('Content-Type', 'application/json')
          header('Accept','application/json')
       }
   }
      
   response {
       status 200  
        body ("""

[
    {
        "id": 0,
        "category": {
            "id": 0,
            "name": "someName"
        },
        "name": "doggie",
        "photoUrls": [
            "somePhotoUrls"
        ],
        "tags": [
            {
                "id": 0,
                "name": "someName"
            }
        ],
        "status": "someStatus"
    }
]
            
""")
        headers {
          header('Content-Type', 'application/json')
        }
        		  
	 }
   }
}
		