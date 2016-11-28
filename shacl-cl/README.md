# Command Line Tool Wrapping TopBraid SHACL API

**An open source implementation which simply wraps the SHACL API provided by Holger Knublauch (holger@topquadrant.com).**

The tool loads all shacl file(s) and rdf file(s) to a jena memory model and validates all constraints.
It is able to provide the result in different formats like txt or csv and allows filtering before generating the output. 

# Command Description

	# Name
		ShaclValidator
	
	# SYNOPSIS
		ShaclValidator [OPTION] ...
	
	# DESCRIPTION
		-path
			full path to shacl/rdf files 
		-format
			output format TXT, XML, TTL, CSV (default TXT)
		-query
			files containing sparql query to filter output (default defaultquery.rq, which is provided)
		-out
			path and file name of output, if not set result is streamed to system.out
		-debug
			set log level to debug
		-raw
			path and file name if raw result before filtered by the query should be provided 
		-help 
			shows up command line parameters
	
