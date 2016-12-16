package de.herko.shaclcl.validator;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.logging.StreamHandler;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.compose.MultiUnion;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.ResultSet;
import org.apache.jena.query.ResultSetFormatter;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.sparql.resultset.ResultsFormat;
import org.apache.jena.util.FileUtils;
import org.topbraid.shacl.arq.SHACLFunctions;
import org.topbraid.shacl.constraints.ModelConstraintValidator;
import org.topbraid.shacl.util.ModelPrinter;
import org.topbraid.shacl.vocabulary.SH;
import org.topbraid.spin.arq.ARQFactory;
import org.topbraid.spin.util.JenaUtil;
import org.topbraid.spin.util.SystemTriples;

/**
 * This class is a simple wrapper for shacl utilities provided by
 * org.topbraid.</br>
 * Call main with command line argument -h or -help to see short info about
 * possible arguments.
 */
public class ShaclValidator {

	// Logger
	private final static Logger LOGGER = Logger.getLogger("ShaclValidator");

	// create Options object
	static Options options = new Options();

	// to keep command line parameter
	static String path = null;
	static String outputFile = null;
	static String format = "TXT"; // default
	static String queryFile = "defaultquery.rq";
	static String queryStr = null;
	static boolean debug = false; // �might be used later
	static String raw = null;

	
	/**
	 * Loads SHACL file(s) and rdf file(s) and validates all constraints.
	 */
	public static void main(String[] args) {
		// some param used for command line parameter

		// setup simple logger to System.out
		setupLogger();
		LOGGER.log(Level.FINE, "Start processing SHACL validation.");

		try {
			// handle command line
			createCommandLineOptions();
			handleCommandLineOptions(args);
			// get fileList
			File pathFile = new File(path);
			// make some checks
			if (!pathFile.isDirectory() && !pathFile.canRead()) {
				System.err.println("Reading path failed.  Reason: unreadable");
				printHelp();
				System.exit(1);
			} else if (pathFile.list(new LocalFileFilter()).length == 0) {
				System.err.println("Reading path path.  Reason: empty");
				printHelp();
				System.exit(1);
			}

			LOGGER.log(Level.FINE, "Start loading files from " + pathFile.getAbsolutePath() + ".");
			Model dataModel = JenaUtil.createMemoryModel();
			for (File file : pathFile.listFiles(new LocalFileFilter())) {
				LOGGER.log(Level.FINE, "Loading file " + file.getAbsolutePath() + " to Model.");
				Model dataModelTmp = JenaUtil.createMemoryModel();
				FileInputStream is = new FileInputStream(file);
				dataModelTmp.read(is, null, checkForFileLang(file.getName()));
				dataModel.add(dataModelTmp);
			}
			LOGGER.log(Level.FINE, "Finished loading files from " + pathFile.getAbsolutePath() + ".");
			// Load the shapes Model (here, includes the dataModel because that
			// has
			// shape definitions too)SHACLSystemModel
			Model shaclModel = ShaclValidator.getSHACLModel();

			MultiUnion unionGraph = new MultiUnion(new Graph[] { shaclModel.getGraph(), dataModel.getGraph()});
			Model shapesModel = ModelFactory.createModelForGraph(unionGraph);

			// Make sure all sh:Functions are registered
			SHACLFunctions.registerFunctions(shapesModel);

			// Create Dataset that contains both the main query model and the
			// shapes
			// model
			// (here, using a temporary URI for the shapes graph)
			URI shapesGraphURI = URI.create("urn:x-shacl-shapes-graph:" + UUID.randomUUID().toString());
			Dataset dataset = ARQFactory.get().getDataset(dataModel);
			dataset.addNamedModel(shapesGraphURI.toString(), shapesModel);
			LOGGER.log(Level.FINE, "Start validating.");
			// Run the validator
			ModelConstraintValidator mcv = new ModelConstraintValidator();
			Model results = mcv.validateModel(dataset, shapesGraphURI, null, true,null, null).getModel();
			LOGGER.log(Level.FINE, "Finished validating.");
			results.setNsPrefix("sh", "http://www.w3.org/ns/shacl#");

			// Print violations
			if (raw != null){
				ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
				String resultOutput = ModelPrinter.get().print(results);
				if (resultOutput != null) {
					outputStream.write(resultOutput.getBytes());
					writeResult(outputStream, raw);
				}
				outputStream.close();
				
			}			
			// System.out.println( ResultSetFormatter.asText((ResultSet)
			// results));
			// System.out.println(ModelPrinter.get().print(results));

			//filter using query
			ResultSet resultSet = filterResult(results, queryStr);
			// formating result using query
			ByteArrayOutputStream resultStream = formatOutput(resultSet, format);
			// dependent on outputfilename the resultStream is pushed to file or
			// STD.out
			writeResult(resultStream, outputFile);
			resultStream.close();
		} catch (ParseException | IOException | InterruptedException e) {
			LOGGER.log(Level.SEVERE, "Critical error! \n"+e.getMessage(), e);
			e.printStackTrace();
		}
		LOGGER.log(Level.FINE, "Finished processing SHACL validation.");
	}

	/**
	 * Method checks command line parameter
	 * @param args String[] original command line parameters
	 * @throws ParseException possible parsing the command line cause exception
	 * @throws IOException possible file handling cause exception
	 */
	private static void handleCommandLineOptions(String[] args) throws ParseException, IOException {
		CommandLineParser parser = new DefaultParser();

		CommandLine cmd = parser.parse(options, args);
		if (cmd.hasOption("help") != false) {
			printHelp();
			System.exit(1);
		}
		path = cmd.getOptionValue("path");
		if (path == null) {
			printHelp();
			System.exit(1);
		}
		if (cmd.getOptionValue("format") != null) {

			if (cmd.getOptionValue("format").matches("(TXT|XML|CSV|TTL|txt|xml|csv|ttl)")) {
				format = cmd.getOptionValue("format").toUpperCase();
			} else {
				System.err.println("Parsing failed. Reason: Unknown format " + cmd.getOptionValue("format"));
				printHelp();
				System.exit(1);
			}
		}
		if (cmd.getOptionValue("query") != null) {
			queryFile = cmd.getOptionValue("query");
		}
		InputStream is = ShaclValidator.class.getClassLoader().getResourceAsStream(queryFile);
		if (is == null) {
			System.err.println("Parsing failed.  Reason: File " + queryFile + " not found.");
			printHelp();
			System.exit(1);
		}
		queryStr = FileUtils.readWholeFileAsUTF8(is);
		if (cmd.getOptionValue("out") != null) {
			outputFile = cmd.getOptionValue("out");
		}

		if (cmd.hasOption("debug") != false) {
			debug = true;
			// set logger output level
			LOGGER.setLevel(Level.FINEST);
		} else {
			LOGGER.setLevel(Level.WARNING);
		}
		if (cmd.getOptionValue("raw") != null){
			raw = cmd.getOptionValue("raw");
		}
	}

	/**
	 * Method writes <code>resultStrem</code> to either <code>STDOUT</code> or
	 * <code>outputFile</code>.
	 * 
	 * @param resultStream
	 *            ByteArrayOutputStream
	 * @param outputFile
	 *            String
	 * @throws IOException
	 *             Writing out might cause exceptions.
	 */
	private static void writeResult(ByteArrayOutputStream resultStream, String outputFile) throws IOException {
		if (resultStream != null && resultStream.size() > 0) {
			if (outputFile == null) {
				LOGGER.log(Level.FINE, "Start output to System.out.");
				// write to stdout
				System.out.println(resultStream.toString());
				LOGGER.log(Level.FINE, "Finished output to System.out.");
			} else {
				LOGGER.log(Level.FINE, "Start output to file " + outputFile + ".");
				// create output file
				File outFile = new File(outputFile);
				LOGGER.log(Level.FINE, "Writing to file " + outFile.getAbsolutePath() + ".");
				FileOutputStream fos = new FileOutputStream(outFile);
				fos.write(resultStream.toByteArray());
				LOGGER.log(Level.FINE, "Finished output to file " + outputFile + ".");
				fos.close();
			}
		}
		if (resultStream != null) {
			try {
				resultStream.close();
			} catch (IOException e) {
				// doesn't matter if not closeable,
			}
		}

	}

	/**
	 * Method queries the <code>Jena Model</code>.
	 * @param result Model to process
	 * @param queryStr String
	 * @return ResultSet result from query
	 */
	private static ResultSet filterResult(Model result, String queryStr) {
		ResultSet queryResult = null;
		LOGGER.log(Level.FINE, "Start filtering using sparql.");

		Query query = QueryFactory.create(queryStr);

		QueryExecution qe = QueryExecutionFactory.create(query, result);

		queryResult = qe.execSelect();

		queryResult.getResourceModel().setNsPrefix("sh", "http://www.w3.org/ns/shacl#");
		// close this resource
		//qe.close();
		LOGGER.log(Level.FINE, "Finished filtering using sparql.");
		return queryResult;
	}
	/**
	 * Method filters the given <code>result</code> using sparql and provides
	 * byte array stream containing the result of </br>
	 * the sparql query using the provided <code>format</code>.</br>
	 * The different formats are created using standard jena
	 * <code>ResultSetFormatter</code>
	 * 
	 * @param result
	 *            Result from shacl validation
	 * @param queryStr
	 *            String containing sparql query
	 * @param format
	 *            String switching output format, currently only provided TXT,
	 *            CSV, XML
	 * @return ByteArrayOutputStream
	 * @throws IOException
	 *             Writing to stream might cause problems
	 */
	@SuppressWarnings("unused")
	private static ByteArrayOutputStream formatOutput(Model result, String queryStr, String format) throws IOException {
		LOGGER.log(Level.FINE, "Start filtering using sparql.");
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

		Query query = QueryFactory.create(queryStr);

		QueryExecution qe = QueryExecutionFactory.create(query, result);

		ResultSet queryResult = qe.execSelect();

		queryResult.getResourceModel().setNsPrefix("sh", "http://www.w3.org/ns/shacl#");
		if (format.matches("(CSV)")) {
			ResultSetFormatter.output(outputStream, queryResult, ResultsFormat.FMT_RS_CSV);
		} else if (format.matches("(XML)")) {
			ResultSetFormatter.outputAsXML(outputStream, queryResult);
		}  else if (format.matches("(TTL)")) {
			ResultSetFormatter.output(outputStream, queryResult, ResultsFormat.FMT_RDF_TTL);
		} else {
			// output txt
			String resultOutput = ResultSetFormatter.asText(queryResult);
			if (resultOutput != null) {
				outputStream.write(resultOutput.getBytes());
			}
		}
		// close this resource
		qe.close();
		
		LOGGER.log(Level.FINE, "Finished filtering.");
		return outputStream;

	}
	
	/**
	 * Method formats the <code>ResultSet</code> using the given format TXT, CSV, XML or TTL. Where as TXT is used as default.
	 * @param queryResult ResultSet
	 * @param format String TXT, CSV, XML or TTL
	 * @return ByteArrayOutputStream prepared for output
	 * @throws IOException
	 */
	private static ByteArrayOutputStream formatOutput(ResultSet queryResult, String format) throws IOException {
		LOGGER.log(Level.FINE, "Start formating to "+format+".");
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

		if (format.matches("(CSV)")) {
			ResultSetFormatter.output(outputStream, queryResult, ResultsFormat.FMT_RS_CSV);
		} else if (format.matches("(XML)")) {
			ResultSetFormatter.outputAsXML(outputStream, queryResult);
		} else if (format.matches("(TTL)")) {
			ResultSetFormatter.output(outputStream, queryResult, ResultsFormat.FMT_RDF_TTL);
		} else {
			// output txt
			String resultOutput = ResultSetFormatter.asText(queryResult);
			if (resultOutput != null) {
				outputStream.write(resultOutput.getBytes());
			}
		}
		LOGGER.log(Level.FINE, "Finished formating to "+format+".");
		return outputStream;
	}

	/**
	 * Method to setup possible command line parameter.
	 */
	private static void createCommandLineOptions() {
		// add t option
		options.addOption("p", "path", true, "Full Path to loadable files (.RDF, .TTL).");
		options.addOption("f", "format", true, "Output format, possible values TXT (default); CSV; XML; TTL");
		options.addOption("q", "query", true, "Full Path to file containing one sparql query to filter shacl result.");
		options.addOption("o", "out", true, "File name and path to output file.");
		options.addOption("d", "debug", false, "Debug mode, creates some additional output info.");
		options.addOption("h", "help", false, "Prints this info.");
		options.addOption("r", "raw", true, "Path/File name for raw output before filtering using TTL output format.");
	}

	/**
	 * Method print command line parameter to STDOUT.
	 */
	private static void printHelp() {
		// automatically generate the help statement
		HelpFormatter formatter = new HelpFormatter();
		formatter.printHelp("ShaclValidator", options);
	}

	/**
	 * Method checks file format simply by mapping the file extensions to
	 * expected format.</br>
	 * <ul>
	 * <li>extension .ttl --> FileUtils.langTurtle.</li>
	 * <li>extension .rdf --> FileUtils.langXML.</li>
	 * <li>extension .xml --> FileUtils.langXML.</li>
	 * </ul>
	 * 
	 * @param filename
	 * @return String
	 */
	private static String checkForFileLang(String filename) {

		if (filename.endsWith(".ttl")) {
			return FileUtils.langTurtle;
		} else if (filename.endsWith(".xml") || filename.endsWith(".rdf")) {
			return FileUtils.langXML;
		} else {
			return "";
		}
	}

	/**
	 * Method loads mandatory triples needed for shacl validation. These are
	 * provided by TopBraid and part of the shacl jar.
	 * 
	 * @return Model jena
	 */
	public static Model getSHACLModel() {
		Model shaclModel = null;
		if (shaclModel == null) {
			shaclModel = JenaUtil.createDefaultModel();
			
			InputStream shaclTTL = ShaclValidator.class.getResourceAsStream("/etc/shacl.ttl");
			shaclModel.read(shaclTTL, SH.BASE_URI, FileUtils.langTurtle);
			
			InputStream dashTTL = ShaclValidator.class.getResourceAsStream("/etc/dash.ttl");
			shaclModel.read(dashTTL, SH.BASE_URI, FileUtils.langTurtle);
			
			InputStream toshTTL = ShaclValidator.class.getResourceAsStream("/etc/tosh.ttl");
			shaclModel.read(toshTTL, SH.BASE_URI, FileUtils.langTurtle);
			
			shaclModel.add(SystemTriples.getVocabularyModel());
			
			SHACLFunctions.registerFunctions(shaclModel);
			
		}
		return shaclModel;
	}

	/**
	 * Method configures the LOGGER
	 */
	private static void setupLogger() {
		LOGGER.setUseParentHandlers(false);
		if (System.getProperty("java.util.logging.SimpleFormatter.format") == null) {
			System.setProperty("java.util.logging.SimpleFormatter.format",
					"%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS %4$s %3$s %5$s%n");
		}
		SimpleFormatter fmt = new SimpleFormatter();

		final StreamHandler seh = new StreamHandler(System.out, fmt) {
			@Override
			public synchronized void publish(final LogRecord record) {
				super.publish(record);
				flush();
			}
		};
		seh.setLevel(Level.ALL);
		LOGGER.addHandler(seh);
	}
}

/**
 * Class to allow only files using extensions ttl, rdf and xml
 */
class LocalFileFilter implements FilenameFilter {
	public boolean accept(File dir, String name) {
		if (name.endsWith(".ttl") || name.endsWith(".rdf") || name.endsWith(".xml")) {
			return true;
		} else {
			return false;
		}
	}
}