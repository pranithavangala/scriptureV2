package nextgen.core.readers;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;

import broad.core.parser.CommandLineParser;
import broad.core.parser.StringParser;




/**
 * @author prussell
 *
 */
public class WigReader {
	
	private BufferedReader reader;
	private Map<String, Map<Integer,Double>> data;

	/**
	 * Instantiate with file name
	 * @param fileName Wig file name
	 * @throws IOException
	 */
	public WigReader(String fileName) throws IOException {
		 readFile(fileName);
	}
	
	/**
	 * Get the data as a map associating chromosome with position and value
	 * @return The wig data as a map
	 */
	public Map<String, Map<Integer,Double>> getWigData() {
		return data;
	}
	
	/**
	 * Read the file and store data
	 * @param fileName File name
	 * @throws IOException
	 */
	private void readFile(String fileName) throws IOException {
		data = new TreeMap<String, Map<Integer,Double>>();
		FileReader r = new FileReader(fileName);
		reader = new BufferedReader(r);
		ArrayList<String> tempDeclarationLine = new ArrayList<String>();
		ArrayList<String> tempDataLines = new ArrayList<String>();
		boolean started = false;
		while(reader.ready()) {
			String line = reader.readLine();
			if(isDeclarationLine(line)) {
				if(started) {
					String lastDeclarationLine = tempDeclarationLine.get(tempDeclarationLine.size() - 1);
					WigSectionReader sectionReader = getSectionReader(lastDeclarationLine, tempDataLines);
					String chr = sectionReader.getChrName();
					if(!data.containsKey(chr)) {
						Map<Integer,Double> chrData = new TreeMap<Integer,Double>();
						data.put(chr, chrData);
					}
					data.get(chr).putAll(sectionReader.getSectionData());
				} 
				tempDeclarationLine.clear();
				tempDataLines.clear();
				tempDeclarationLine.add(line);
				started = true;
			} else {
				tempDataLines.add(line);
			}
		}
		if(started) {
			String lastDeclarationLine = tempDeclarationLine.get(tempDeclarationLine.size() - 1);
			WigSectionReader sectionReader = getSectionReader(lastDeclarationLine, tempDataLines);
			String chr = sectionReader.getChrName();
			if(!data.containsKey(chr)) {
				Map<Integer,Double> chrData = new TreeMap<Integer,Double>();
				data.put(chr, chrData);
			}
			data.get(chr).putAll(sectionReader.getSectionData());
		} 		
		r.close();
		reader.close();
	}
	
	/**
	 * Whether the line appears to be a section declaration line
	 * @param line The line
	 * @return True iff the line is a declaration line
	 */
	private static boolean isDeclarationLine(String line) {
		StringParser p = new StringParser();
		p.parse(line);
		return(p.asString(0).contains("Step"));
	}
	
	/**
	 * Get section reader to process the section of wig data
	 * @param declarationLine Declaration line
	 * @param dataLines List of data lines following declaration line
	 * @return Reader to process the section
	 */
	private WigSectionReader getSectionReader(String declarationLine, ArrayList<String> dataLines) {
		WigDeclarationLine line = new WigDeclarationLine(declarationLine);
		if(line.isFixedStep()) {
			if(!line.hasStart()) {
				throw new IllegalArgumentException("Fixed step declaration line must specify start position");
			}
			if(!line.hasStep()) {
				throw new IllegalArgumentException("Fixed step declaration line must specify step size");
			}
			return new FixedStepSectionReader(declarationLine, dataLines);
		}
		return new VariableStepSectionReader(declarationLine, dataLines);
	}
	
	
	/**
	 * @param args
	 * @throws IOException
	 */
	public static void main(String[] args) throws IOException {
		
		CommandLineParser p = new CommandLineParser();
		p.addStringArg("-i", "Input wig file", true);
		p.addStringArg("-o", "Output table of positions and values", true);
		p.parse(args);
		String inWig = p.getStringArg("-i");
		String outWig = p.getStringArg("-o");
		
		WigReader wr = new WigReader(inWig);
		Map<String, Map<Integer, Double>> data = wr.getWigData();
		FileWriter w = new FileWriter(outWig);
		for(String chr : data.keySet()) {
			for(Integer pos : data.get(chr).keySet()) {
				w.write(chr + "\t" + pos.intValue() + "\t" + data.get(chr).get(pos).doubleValue() + "\n");
			}
		}
		w.close();
		
	}
	
	/**
	 * Section reader to process a section of wig data
	 * @author prussell
	 *
	 */
	private interface WigSectionReader {
		
		/**
		 * Process the section and store data
		 */
		public void processSection();
		
		/**
		 * Get the data
		 * @return Map associating position with data value
		 */
		public Map<Integer, Double> getSectionData();
		
		/**
		 * Get the chromosome name associated with the data section
		 * @return Chromosome name
		 */
		public String getChrName();
		
	}
	
	
	/**
	 * Reader to process a variable step section of a wig file
	 * @author prussell
	 *
	 */
	private class VariableStepSectionReader implements WigSectionReader {
		
		private String chrName;
		private WigDeclarationLine wigDeclarationLine;
		private int span;
		private ArrayList<String> wigDataLines;
		private Map<Integer, Double> sectionData;
		private StringParser stringParser;

		protected VariableStepSectionReader(String declarationLine, ArrayList<String> dataLines) {
			wigDeclarationLine = new WigDeclarationLine(declarationLine);
			chrName = wigDeclarationLine.getChr();
			span = wigDeclarationLine.getSpan();
			wigDataLines = dataLines;
			sectionData = new TreeMap<Integer, Double>();
			stringParser = new StringParser();
			processSection();
		}

		@Override
		public void processSection() {
			for(String line : wigDataLines) {
				stringParser.parse(line);
				int startPos = stringParser.asInt(0);
				double value = stringParser.asDouble(1);
				for(int pos = startPos; pos < startPos + span; pos++) {
					sectionData.put(Integer.valueOf(pos), Double.valueOf(value));
				}
			}
		}

		@Override
		public Map<Integer, Double> getSectionData() {
			return sectionData;
		}

		@Override
		public String getChrName() {
			return chrName;
		}
		
	}
	
	/**
	 * Reader to process a fixed step section of a wig file
	 * @author prussell
	 *
	 */
	private class FixedStepSectionReader implements WigSectionReader  {

		private String chrName;
		private WigDeclarationLine wigDeclarationLine;
		private int span;
		private int start;
		private int step;
		private ArrayList<String> wigDataLines;
		private Map<Integer, Double> sectionData;
		private StringParser stringParser;

		protected FixedStepSectionReader(String declarationLine, ArrayList<String> dataLines) {
			wigDeclarationLine = new WigDeclarationLine(declarationLine);
			chrName = wigDeclarationLine.getChr();
			span = wigDeclarationLine.getSpan();
			start = wigDeclarationLine.getStart();
			step = wigDeclarationLine.getStep();
			wigDataLines = dataLines;
			sectionData = new TreeMap<Integer, Double>();
			stringParser = new StringParser();
			processSection();
		}

		@Override
		public void processSection() {
			int pos = start;
			for(String line : wigDataLines) {
				stringParser.parse(line);
				double value = stringParser.asDouble(0);
				for(int currPos = pos; currPos < pos + span; currPos++) {
					sectionData.put(Integer.valueOf(currPos), Double.valueOf(value));
				}
				pos += step;
			}
		}

		@Override
		public Map<Integer, Double> getSectionData() {
			return sectionData;
		}

		@Override
		public String getChrName() {
			return chrName;
		}
		
	}
	
	protected static String WIG_SPEC_VARIABLE_STEP = "variableStep";
	protected static String WIG_SPEC_FIXED_STEP = "fixedStep";
	protected static String WIG_SPEC_CHROM = "chrom";
	protected static String WIG_SPEC_SPAN = "span";
	protected static String WIG_SPEC_START = "start";
	protected static String WIG_SPEC_STEP = "step";
	
	/**
	 * Declaration line for a section of a wig file
	 * @author prussell
	 *
	 */
	private class WigDeclarationLine {
		
		/**
		 * The declaration line
		 */
		private String declarationLine;
		
		/**
		 * The specification values by type
		 */
		private Map<String, String> specs;
		
		/**
		 * Whether the section is fixed step as opposed to variable step
		 */
		private boolean fixedStep;

		/**
		 * Construct with the declaration line
		 * @param line The declaration line
		 */
		protected WigDeclarationLine(String line) {
			declarationLine = line;
			parseLine();
		}
		
		/**
		 * Parse the line
		 * Store whether the section is fixed step
		 * Store spec values
		 */
		protected void parseLine() {
			specs = new TreeMap<String,String>();
			String[] tokens = StringParser.getTokens(declarationLine);
			StringParser p = new StringParser();
			for(int i=0; i < tokens.length; i++) {
				String token = tokens[i];
				if(token.equals(WIG_SPEC_FIXED_STEP)) {
					fixedStep = true;
					continue;
				}
				if(token.equals(WIG_SPEC_VARIABLE_STEP)) {
					fixedStep = false;
					continue;
				}
				p.parse(token,"=");
				specs.put(p.asString(0), p.asString(1));
			}
			
		}
		
		/**
		 * Whether the section is fixed step as opposed to variable step
		 * @return True iff the section is fixed step
		 */
		protected boolean isFixedStep() {
			return fixedStep;
		}
		
		/**
		 * The chromosome name specified in the line
		 * @return Chromosome name for the section
		 */
		protected String getChr() {
			if(!specs.containsKey(WIG_SPEC_CHROM)) {
				throw new IllegalStateException("Declaration line does not declare chromosome");
			}
			return specs.get(WIG_SPEC_CHROM);
		}
		
		/**
		 * The span specified in the line
		 * @return Span for the section
		 */		
		protected int getSpan() {
			if(!specs.containsKey(WIG_SPEC_SPAN)) {
				return 1;
			}
			return Integer.parseInt(specs.get(WIG_SPEC_SPAN));			
		}
		
		/**
		 * Whether a start position is specified on the line
		 * @return True iff a start is specified
		 */
		protected boolean hasStart() {
			return specs.containsKey(WIG_SPEC_START);
		}
		
		/**
		 * The start position specified in the line
		 * @return The start position for the section
		 */
		protected int getStart() {
			if(!specs.containsKey(WIG_SPEC_START)) {
				throw new IllegalStateException("Declaration line does not declare start");
			}
			return Integer.parseInt(specs.get(WIG_SPEC_START));						
		}
		
		/**
		 * Whether a step size is specified on the line
		 * @return True iff a step size is specified
		 */
		protected boolean hasStep() {
			return specs.containsKey(WIG_SPEC_STEP);
		}
		
		/**
		 * The step size specified in the line
		 * @return The step size for the section
		 */
		protected int getStep() {
			if(!specs.containsKey(WIG_SPEC_STEP)) {
				throw new IllegalStateException("Declaration line does not declare step");
			}
			return Integer.parseInt(specs.get(WIG_SPEC_STEP));						
		}

		
	}

	
}