/**
 * 
 */
package nextgen.synbio;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import broad.core.parser.CommandLineParser;
import broad.core.primer3.PrimerPair;
import broad.core.primer3.PrimerUtils;
import broad.core.sequence.FastaSequenceIO;
import broad.core.sequence.Sequence;

/**
 * @author prussell
 *
 */
public class GibsonAssemblyOligoPool {
	
	private Collection<Sequence> sequences;
	private Collection<TypeIISRestrictionEnzyme> restrictionEnzymes;
	private Map<String, Integer> kmerCounts;
	private int primerSize;
	private int assemblyOverlapSize;
	private int fullOligoSize;
	private static int DEFAULT_PRIMER_SIZE = 15;
	private static int DEFAULT_OVERLAP_SIZE = 40;
	private static int DEFAULT_OLIGO_SIZE = 200;
	protected static String THROWAWAY_BASE = "T";
	static Logger logger = Logger.getLogger(GibsonAssemblyOligoPool.class.getName());
	private String primer3core;
	
	/**
	 * @param seqs Sequences to assemble
	 * @param enzymes Possible restriction enzymes to use
	 * @param oligoSize Size of full oligos
	 * @param overlapSize Size of overlap for Gibson assembly
	 * @param primerLength Length of primers to amplify oligos
	 * @param primer3coreExecutable primer3core executable file
	 */
	private GibsonAssemblyOligoPool(Collection<Sequence> seqs, Collection<TypeIISRestrictionEnzyme> enzymes, int oligoSize, int overlapSize, int primerLength, String primer3coreExecutable) {
		logger.info("Constructing oligo pool object...");
		if(overlapSize + 2*primerLength >= oligoSize) {
			throw new IllegalArgumentException("Oligo size must be larger than overlap size + 2 * primer length");
		}
		if(oligoSize <= 0 || overlapSize <= 0 || primerLength <= 0) {
			throw new IllegalArgumentException("All length parameters must be positive");
		}
		sequences = seqs;
		restrictionEnzymes = enzymes;
		assemblyOverlapSize = overlapSize;
		primerSize = primerLength;
		fullOligoSize = oligoSize;
		primer3core = primer3coreExecutable;
		buildKmerMap();
		logger.info("Done constructing oligo pool object.");
	}
	
	/**
	 * Build map of kmer counts
	 * Will be used to make sure assembly overlaps are unique in the transcripts
	 */
	private void buildKmerMap() {
		logger.info("Building kmer map for overlaps...");
		kmerCounts = new TreeMap<String, Integer>();
		for(Sequence seq : sequences) {
			logger.debug(seq.getId());
			String bases = seq.getSequenceBases();
			int pos = 0;
			while (pos < seq.getLength() - assemblyOverlapSize + 1) {
				String kmerStr = bases.substring(pos,pos + assemblyOverlapSize).toUpperCase();
				if(kmerCounts.containsKey(kmerStr)) {
					kmerCounts.put(kmerStr, Integer.valueOf(kmerCounts.get(kmerStr).intValue()+1));
				} else {
					kmerCounts.put(kmerStr, Integer.valueOf(1));
				}
				pos++;
			}
		}
		int occurrences = 0;
		for(String kmer : kmerCounts.keySet()) {
			occurrences += kmerCounts.get(kmer).intValue();
		}
		logger.info("Done building kmer map. There are " + kmerCounts.size() + " different " + assemblyOverlapSize + "-mers with a total of " + occurrences + " occurrences.");
	}
	
	/**
	 * If enzyme cuts 5' of recognition site on bottom strand, some bases will be lost in the recession step of Gibson assembly
	 * as the top strand is chewed from the 5' end. This method calculates the number of throwaway bases that should be added to
	 * oligos between the recognition site and the core sequence so the throwaway bases will be lost instead of real sequence
	 * @param enzyme The enzyme
	 * @return The number of throwaway bases to add
	 */
	protected static int getNumThrowawayBases(TypeIISRestrictionEnzyme enzyme) {
		return Math.max(0, -1 * enzyme.getBottomStrandCleavageSite());
	}
	
	/**
	 * Break the transcript into overlapping subsequences for Gibson assembly
	 * @param parentSequence The transcript
	 * @param length Length of subsequences
	 * @return The subsequences with appropriate overlaps
	 */
	private Collection<Subsequence> designOverlappingSequences(Sequence parentSequence, int length) {
		logger.info("Designing overlapping subsequences of length " + length + " for transcript " + parentSequence.getId() + "...");
		if(length <= assemblyOverlapSize) {
			throw new IllegalArgumentException("Subsequence length (" + length + ") must be larger than overlap size (" + assemblyOverlapSize + ")");
		}
		Collection<Subsequence> rtrn = new ArrayList<Subsequence>();
		String seqBases = parentSequence.getSequenceBases();
		// If sequence is shorter than desired length, check for unique ends and then just return the sequence
		if(parentSequence.getLength() <= length) {
			if(!rightEndUnique(seqBases) || !leftEndUnique(seqBases)) {
				logger.warn("Sequence "+ parentSequence.getId() + " is shorter than desired length and ends are not unique. Returning empty collection.");
				return rtrn;
			}
			rtrn.add(new Subsequence(parentSequence, 0, parentSequence.getLength()));
			return rtrn;
		}

		// Keep track of the subsequences in order so can go back and modify last one
		ArrayList<Subsequence> subsequences = new ArrayList<Subsequence>();
		
		// Add an initial subsequence
		Subsequence firstSubseq = new Subsequence(parentSequence, 0, length);
		logger.debug("Trying initial subsequence " + firstSubseq.getSequence() + ".");
		boolean leftOk = false;
		while(!leftOk) {
			if(leftEndUnique(firstSubseq)) {
				leftOk = true;
				logger.debug("Left end is unique.");
				continue;
			}
			firstSubseq.moveStartAndChangeSize(1);
			logger.debug("Left end is not unique. Trying " + firstSubseq.getSequence() + ".");
		}
		boolean rightOk = false;
		while(!rightOk) {
			if(rightEndUnique(firstSubseq)) {
				rightOk = true;
				logger.debug("Right end is unique.");
				continue;
			}
			firstSubseq.moveEndAndChangeSize(-1);
			logger.debug("Right end is not unique. Trying " + firstSubseq.getSequence() + ".");
		}
		logger.debug("Adding " + firstSubseq.getSequence() + " with start position " + firstSubseq.getStartOnParent() + ".");
		subsequences.add(firstSubseq);
		int currentSubseq = 1;
		int lastSubseqEnd = subsequences.get(currentSubseq - 1).getEndOnParent();
		
		// Go through entire transcript
		while(lastSubseqEnd - assemblyOverlapSize < parentSequence.getLength() - length) {
			
			Subsequence newSubseq = new Subsequence(parentSequence, lastSubseqEnd - assemblyOverlapSize, length);
			logger.debug("Trying subsequence " + newSubseq.getSequence() + ".");
			boolean newLeftOk = false;
			while(!newLeftOk) {
				if(leftEndUnique(newSubseq)) {
					newLeftOk = true;
					logger.debug("Left end is unique.");
					continue;
				}
				newSubseq.shift(-1);
				subsequences.get(currentSubseq - 1).moveEndAndChangeSize(-1);
				logger.debug("Left end is not unique. Changing to " + newSubseq.getSequence() + " and changing previous sequence to " + subsequences.get(currentSubseq - 1).getSequence() + ".");
			}
			logger.debug("Adding " + newSubseq.getSequence() + " with start position " + newSubseq.getStartOnParent() + ".");
			subsequences.add(newSubseq);
			currentSubseq++;
			lastSubseqEnd = subsequences.get(currentSubseq - 1).getEndOnParent();			
		}
		
		// Add one more sequence at the end and modify the previous one accordingly
		int finalSubseqLength = parentSequence.getLength() - (lastSubseqEnd - assemblyOverlapSize);
		if(!(finalSubseqLength <= length)) {
			throw new IllegalStateException("Leftover region is too big (" + finalSubseqLength + ")");
		}
		Subsequence finalSubseq = new Subsequence(parentSequence, lastSubseqEnd - assemblyOverlapSize, finalSubseqLength);
		logger.debug("Trying final subsequence " + finalSubseq.getSequence() + ".");
		boolean finalRightOk = false;
		while(!finalRightOk) {
			if(rightEndUnique(finalSubseq)) {
				finalRightOk = true;
				logger.debug("Right end is unique.");
				continue;
			}
			finalSubseq.moveEndAndChangeSize(-1);
			logger.debug("Right end is not unique. Trying " + finalSubseq.getSequence() + ".");
		}
		logger.debug("Adding " + finalSubseq.getSequence() + " with start position " + finalSubseq.getStartOnParent() + ".");
		subsequences.add(finalSubseq);

		return subsequences;
	}
	
	/**
	 * Checks whether the left end of the sequence of size equal to the overlap size is unique in the transcript set
	 * @param sequence The sequence
	 * @return Whether the left end is unique
	 */
	private boolean leftEndUnique(Subsequence sequence) {
		return leftEndUnique(sequence.getSequence());
	}

	/**
	 * Checks whether the right end of the sequence of size equal to the overlap size is unique in the transcript set
	 * @param sequence The sequence
	 * @return Whether the right end is unique
	 */
	private boolean rightEndUnique(Subsequence sequence) {
		return rightEndUnique(sequence.getSequence());
	}

	/**
	 * Checks whether the left end of the sequence of size equal to the overlap size is unique in the transcript set
	 * @param sequence The sequence
	 * @return Whether the left end is unique
	 */
	private boolean leftEndUnique(String sequence) {
		String leftEnd = sequence.substring(0, assemblyOverlapSize);
		boolean leftUnique = kmerCounts.get(leftEnd).equals(Integer.valueOf(1));
		if(!leftUnique) {
			logger.info("Sequence does not have unique ends: kmer " + leftEnd + " appears " + kmerCounts.get(leftEnd).toString() + " times.");
			return false;
		}
		return true;
	}
	
	/**
	 * Checks whether the right end of the sequence of size equal to the overlap size is unique in the transcript set
	 * @param sequence The sequence
	 * @return Whether the right end is unique
	 */
	private boolean rightEndUnique(String sequence) {
		String rightEnd = sequence.substring(sequence.length() - assemblyOverlapSize, sequence.length());
		boolean rightUnique = kmerCounts.get(rightEnd).equals(Integer.valueOf(1));
		if(!rightUnique) {
			logger.info("Sequence does not have unique ends: kmer " + rightEnd + " appears " + kmerCounts.get(rightEnd).toString() + " times.");
			return false;
		}
		return true;
	}
	
	
	/**
	 * Get the core sequence length that will bring oligo to the appropriate size depending on primer size, recognition sequence, and number of throwaway bases
	 * @param enzyme Enzyme which determines number of throwaway bases
	 * @return The core sequence length to use
	 */
	private int getCoreSequenceLengthWithinOligo(TypeIISRestrictionEnzyme enzyme) {
		int rtrn = fullOligoSize - 2 * primerSize - enzyme.getTopStrandRecognitionSite().length() - enzyme.getBottomStrandRecognitionSite().length() - 2 * getNumThrowawayBases(enzyme);
		if(rtrn <= 0) {
			throw new IllegalArgumentException("Pieces other than core sequence add up to >= the full oligo size");
		}
		return rtrn;
	}
	
	/**
	 * Design full oligos for the transcript including primers and cut sites
	 * @param parentSequence The transcript
	 * @return The collection of oligos with primers for PCR amplification and cut sites for removal of primers
	 * @throws IOException 
	 */
	private Collection<FullOligo> designOligos(Sequence parentSequence) throws IOException {
		
		/*
		 * Choose a restriction enzyme whose recognition sequence does not appear in the transcript
		 * The enzyme determines the subsequence length
		 */
		logger.info("");
		logger.info("Choosing an enzyme for transcript " + parentSequence.getId() + "...");
		TypeIISRestrictionEnzyme enzyme = null;
		for(TypeIISRestrictionEnzyme e : restrictionEnzymes) {
			logger.info("Trying " + e.getName() + ".");
			String r = e.getTopStrandRecognitionSite();
			if(parentSequence.contains(r)) {
				logger.warn("Sequence " + parentSequence.getId() + " contains " + e.getName() + " recognition sequence " + r + ". Can't use enzyme.");
				continue;
			}
			String rr = Sequence.reverseSequence(r);
			if(parentSequence.contains(rr)) {
				logger.warn("Sequence " + parentSequence.getId() + " contains " + e.getName() + " reverse of recognition sequence " + r + ". Can't use enzyme.");
				continue;
			}
			enzyme = e;
		}
		if(enzyme == null) {
			throw new IllegalArgumentException("Couldn't find a restriction enzyme whose recognition sequence is not contained in transcript " + parentSequence.getId() + ".");
		}
		int length = getCoreSequenceLengthWithinOligo(enzyme);
		String recognitionSeq = enzyme.getTopStrandRecognitionSite();
		String rcRecognitionSeq = Sequence.reverseSequence(recognitionSeq);
		logger.info("Using enzyme " + enzyme.getName() + ". Recognition sequence is " + recognitionSeq + ". Overlapping subsequence length for this enzyme and this oligo setup is " + length + ".");
		
		// Design overlapping subsequences
		Collection<Subsequence> overlappingSeqs = designOverlappingSequences(parentSequence, length);
		logger.info("Got " + overlappingSeqs.size() + " overlapping subsequences.");
		
		/*
		 * Choose a primer pair and create the full oligos
		 * Keep the primer pair if:
		 * Neither primer contains the enzyme recognition sequence (i.e. each oligo contains the recognition sequence once and its reverse once)
		 * Neither primer has a perfect match at its 3' end with any full oligo for the gene (except in the primer sequence itself) (try 8bp first)
		 */
		logger.info("Trying to assign primers for transcript " + parentSequence.getId() + ".");
		boolean foundPrimer = false;
		Collection<FullOligo> rtrn = new TreeSet<FullOligo>();
		while(!foundPrimer) {
			PrimerPair primer = PrimerUtils.getOneSyntheticPrimerPair(primerSize, primer3core);
			String leftPrimer = primer.getLeftPrimer();
			String rightPrimer = primer.getRightPrimer();
			String leftPrimer3primeEnd = leftPrimer.substring(leftPrimer.length() - 8);
			String leftPrimer3primeEndRC = Sequence.reverseSequence(leftPrimer3primeEnd);
			String rightPrimer3primeEnd = rightPrimer.substring(rightPrimer.length() - 8);
			String rightPrimer3primeEndRC = Sequence.reverseSequence(rightPrimer3primeEnd);
			boolean primerOk = true;
			Collection<FullOligo> oligos = new TreeSet<FullOligo>();
			logger.debug("There are " + overlappingSeqs.size() + " overlapping sequences.");
			for(Subsequence seq : overlappingSeqs) {
				FullOligo oligo = new FullOligo(parentSequence, seq, primer, enzyme);
				oligos.add(oligo);
			}
			logger.info("Trying primer pair " + primer.getLeftPrimer() + " " + primer.getRightPrimer() + " for " + oligos.size() + " oligos.");
			for(FullOligo oligo : oligos) {
				String oligoSequence = oligo.getFullSequenceTopStrand().getSequenceBases();
				// Check that each oligo contains the recognition sequence once
				int firstOccurrenceRecognitionSeq = oligoSequence.indexOf(recognitionSeq);
				int lastOccurrenceRecognitionSeq = oligoSequence.lastIndexOf(recognitionSeq);
				int firstOccurrenceRcRecognitionSeq = oligoSequence.indexOf(rcRecognitionSeq);
				int lastOccurrenceRcRecognitionSeq = oligoSequence.lastIndexOf(rcRecognitionSeq);
				if(firstOccurrenceRecognitionSeq == -1) {
					logger.warn("Proposed oligo " + oligoSequence + " does not contain enzyme recognition sequence " + recognitionSeq + ". Rejecting primer pair.");
					primerOk = false;
					break;
				}
				if(firstOccurrenceRcRecognitionSeq == -1) {
					logger.warn("Proposed oligo " + oligoSequence + " does not contain reverse complement of enzyme recognition sequence " + recognitionSeq + ". Rejecting primer pair.");
					primerOk = false;
					break;
				}
				if(firstOccurrenceRecognitionSeq != lastOccurrenceRecognitionSeq) {
					logger.warn("Proposed oligo " + oligoSequence + " contains enzyme recognition sequence " + recognitionSeq + " more than once. Rejecting primer pair.");
					primerOk = false;
					break;
				}
				if(firstOccurrenceRcRecognitionSeq != lastOccurrenceRcRecognitionSeq) {
					logger.warn("Proposed oligo " + oligoSequence + " contains reverse complement of enzyme recognition sequence " + recognitionSeq + " more than once. Rejecting primer pair.");
					primerOk = false;
					break;
				}
				// Check that each oligo contains the 3' ends of the primers once
				int firstOccurrenceLeftPrimer3primeEnd = oligoSequence.indexOf(leftPrimer3primeEnd);
				int lastOccurrenceLeftPrimer3primeEnd = oligoSequence.lastIndexOf(leftPrimer3primeEnd);
				int firstOccurrenceRightPrimer3primeEndRC = oligoSequence.indexOf(rightPrimer3primeEndRC);
				int lastOccurrenceRightPrimer3primeEndRC = oligoSequence.lastIndexOf(rightPrimer3primeEndRC);
				// Check that the two primer ends appear in the oligo
				if(firstOccurrenceLeftPrimer3primeEnd == -1) {
					throw new IllegalStateException("Proposed oligo " + oligoSequence + " does not contain left primer subsequence " + leftPrimer3primeEnd + ".");
				}
				if(firstOccurrenceRightPrimer3primeEndRC == -1) {
					throw new IllegalStateException("Proposed oligo " + oligoSequence + " does not contain reversed right primer subsequence " + rightPrimer3primeEndRC + ".");
				}
				// Check that the two primer ends appear at most once in the oligo
				if(firstOccurrenceLeftPrimer3primeEnd != lastOccurrenceLeftPrimer3primeEnd) {
					logger.warn("Proposed oligo " + oligoSequence + " contains left primer subsequence " + leftPrimer3primeEnd + " more than once. Rejecting primer pair.");
					primerOk = false;
					break;
				}
				if(firstOccurrenceRightPrimer3primeEndRC != lastOccurrenceRightPrimer3primeEndRC) {
					logger.warn("Proposed oligo " + oligoSequence + " contains right primer reversed subsequence " + rightPrimer3primeEndRC + " more than once. Rejecting primer pair.");
					primerOk = false;
					break;
				}
				// Check that the RCed primer ends do not appear at all in the oligo
				if(oligoSequence.contains(leftPrimer3primeEndRC)) {
					logger.warn("Proposed oligo " + oligoSequence + " contains reversed left primer subsequence " + leftPrimer3primeEndRC + ". Rejecting primer pair.");
					primerOk = false;
					break;
				}
				if(oligoSequence.contains(rightPrimer3primeEnd)) {
					logger.warn("Proposed oligo " + oligoSequence + " contains right primer subsequence " + rightPrimer3primeEnd + ". Rejecting primer pair.");
					primerOk = false;
					break;
				}
			}
			if(primerOk) {
				foundPrimer = true;
				rtrn.clear();
				rtrn.addAll(oligos);
				logger.info("Found primer pair for transcript " + parentSequence.getId() + ". Assigning to " + rtrn.size() + " oligos.");
				break;
			}
		}
		
		return rtrn;
		
	}
	
	/**
	 * Design the full array for all transcripts
	 * @return All oligos on the full array
	 * @throws IOException 
	 */
	private Collection<FullOligo> designArray() throws IOException {
		logger.info("");
		logger.info("Designing array...");
		Collection<FullOligo> rtrn = new TreeSet<FullOligo>();
		for(Sequence transcript : sequences) {
			rtrn.addAll(designOligos(transcript));
		}
		logger.info("");
		logger.info("Done designing array.");
		return rtrn;
	}

	/**
	 * Design the full array and write output to a table and a fasta file
	 * @param outPrefix Output file prefix
	 * @throws IOException 
	 */
	private void designAndWriteOutput(String outPrefix) throws IOException {
		Collection<FullOligo> oligos = designArray();
		String outFasta = outPrefix + ".fa";
		String outTable = outPrefix + ".out";
		logger.info("Writing output to " + outFasta + " and " + outTable + "...");
		FileWriter fastaWriter = new FileWriter(outFasta);
		FileWriter tableWriter = new FileWriter(outTable);
		String tableHeader = "Parent_sequence\t";
		tableHeader += "Enzyme\t";
		tableHeader += "Top_strand_recognition_sequence\t";
		tableHeader += "Bottom_strand_recognition_sequence\t";
		tableHeader += "Left_primer\t";
		tableHeader += "Right_primer\t";
		tableHeader += "Subsequence_name\t";
		tableHeader += "Subsequence\t";
		tableHeader += "Full_oligo\t";
		tableWriter.write(tableHeader + "\n");
		for(FullOligo oligo : oligos) {
			String id = "oligo_" + oligo.getCoreTranscriptSequence().getCurrentId();
			// Write fasta sequence
			fastaWriter.write(">" + id + "\n" + oligo.getFullSequenceTopStrand().getSequenceBases() + "\n");
			// Write table line
			String line = oligo.getParentSequence().getId() + "\t";
			line += oligo.getEnzyme().getName() + "\t";
			line += oligo.getEnzyme().getTopStrandRecognitionSite() + "\t";
			line += oligo.getEnzyme().getBottomStrandRecognitionSite() + "\t";
			line += oligo.getPrimerPair().getLeftPrimer() + "\t";
			line += oligo.getPrimerPair().getRightPrimer() + "\t";
			line += oligo.getCoreTranscriptSubsequence().getCurrentId() + "\t";
			line += oligo.getCoreTranscriptSubsequence().getSequence() + "\t";
			line += oligo.getFullSequenceTopStrand().getSequenceBases() + "\t";
			tableWriter.write(line + "\n");

		}
		fastaWriter.close();
		tableWriter.close();
		logger.info("Done writing output.");
	}
	
	private class Subsequence implements Comparable<Subsequence> {
		
		private Sequence parent;
		private int startOnParent;
		private int size;
		
		public Subsequence(Sequence parentSequence, int startPosOnParent, int subsequenceSize) {
			parent = parentSequence;
			startOnParent = startPosOnParent;
			size = subsequenceSize;
			if(startOnParent + size > parent.getLength()) {
				throw new IllegalArgumentException("Sequence " + parentSequence.getId() + " is too short (" + parentSequence.getLength() + " to get a subsequence of length " + subsequenceSize + " starting at position " + startPosOnParent + ".");
			}
		}
		
		public String getCurrentId() {
			return parent.getId() + "_" + startOnParent + "_" + Integer.valueOf(startOnParent + size).toString();
		}
		
		/**
		 * Change start position keeping end position the same
		 * @param newRelStart New start relative to old start
		 */
		public void moveStartAndChangeSize(int newRelStart) {
			setStartOnParent(startOnParent + newRelStart);
			setSize(size - newRelStart);
		}
		
		/**
		 * Change start and end positions keeping size the same
		 * @param newRelStart New start relative to old start
		 */
		public void shift(int newRelStart) {
			setStartOnParent(startOnParent + newRelStart);
		}

		
		/**
		 * Change end position keeping start position the same
		 * @param newRelEnd New end position relative to old end
		 */
		public void moveEndAndChangeSize(int newRelEnd) {
			setSize(size + newRelEnd);
		}
		
		private void setStartOnParent(int start) {
			if(start < 0) {
				throw new IllegalArgumentException("Start position on parent must be >= 0");
			}
			if(start >= parent.getLength()) {
				throw new IllegalArgumentException("Start position on parent must be < " + parent.getLength());
			}
			startOnParent = start;
		}
		
		public int getStartOnParent() {
			return startOnParent;
		}
		
		public int getEndOnParent() {
			return startOnParent + size;
		}
		
		private void setSize(int s) {
			if(size < 0) {
				throw new IllegalArgumentException("Size must be >= 0");
			}
			if(startOnParent + size > parent.getLength()) {
				throw new IllegalArgumentException("Start + size must be <= " + parent.getLength());
			}
			size = s;
		}
		
		public String getSequence() {
			return parent.getSequenceBases().substring(startOnParent, startOnParent + size);
		}

		@Override
		public int compareTo(Subsequence o) {
			if(!parent.getId().equals(o.parent.getId())) {
				return parent.getId().compareTo(o.parent.getId());
			}
			return getStartOnParent() - o.getStartOnParent();
		}
		
	}
	
	/**
	 * An oligo with primers, cut sites, throwaway bases if necessary, and the transcript subsequence
	 * @author prussell
	 */
	private class FullOligo implements Comparable<FullOligo> {
		
		private Sequence parentSequence;
		private TypeIISRestrictionEnzyme restrictionEnzyme;
		private PrimerPair primerPair;
		private Subsequence coreTranscriptSequence;
		private int coreSubsequenceStartOnParent;
		
		/**
		 * @param parent Parent transcript
		 * @param coreSubsequence Subsequence for this oligo
		 * @param primer Primer pair for the transcript
		 * @param enzyme Restriction enzyme for the transcript
		 */
		public FullOligo(Sequence parent, Subsequence coreSubsequence, PrimerPair primer, TypeIISRestrictionEnzyme enzyme) {
			parentSequence = parent;
			coreTranscriptSequence = coreSubsequence;
			primerPair = primer;
			restrictionEnzyme = enzyme;
			coreSubsequenceStartOnParent = coreSubsequence.getStartOnParent();
		}
		
		public Subsequence getCoreTranscriptSequence() {
			return coreTranscriptSequence;
		}

		/**
		 * Get the restriction enzyme to remove primers from this oligo
		 * @return The restriction enzyme
		 */
		public TypeIISRestrictionEnzyme getEnzyme() {
			return restrictionEnzyme;
		}
		
		/**
		 * Get start position on parent transcript
		 * @return Start position on parent transcript
		 */
		public int getStartOnParent() {
			return coreSubsequenceStartOnParent;
		}
		
		/**
		 * Get the primer pair for this oligo
		 * @return The primer pair
		 */
		public PrimerPair getPrimerPair() {
			return primerPair;
		}
		
		/**
		 * Get the transcript subsequence for this oligo
		 * @return The transcript subsequence
		 */
		public Subsequence getCoreTranscriptSubsequence() {
			return coreTranscriptSequence;
		}
		
		/**
		 * Get the full sequence of the top strand of the oligo
		 * @return The full sequence of the oligo
		 */
		public Sequence getFullSequenceTopStrand() {
			String throwawaySeq = "";
			for(int i = 0; i < getNumThrowawayBases(restrictionEnzyme); i++) {
				throwawaySeq += THROWAWAY_BASE;
			}
			String rightPrimerRC = Sequence.reverseSequence(primerPair.getRightPrimer());
			String recognitionSeq = restrictionEnzyme.getTopStrandRecognitionSite();
			String reverseRecognitionSeq = Sequence.reverseSequence(recognitionSeq);
			Sequence rtrn = new Sequence(coreTranscriptSequence.getCurrentId());
			rtrn.setSequenceBases(primerPair.getLeftPrimer() + recognitionSeq + throwawaySeq + coreTranscriptSequence.getSequence() + throwawaySeq + reverseRecognitionSeq + rightPrimerRC);
			return rtrn;
		}
		
		/**
		 * Get the parent transcript this oligo is part of
		 * @return The parent transcript
		 */
		public Sequence getParentSequence() {
			return parentSequence;
		}
		
		
		@Override
		public int compareTo(FullOligo o) {
			if(!parentSequence.getId().equals(o.parentSequence.getId())) {
				return parentSequence.getId().compareTo(o.parentSequence.getId());
			}
			return getStartOnParent() - o.getStartOnParent();
		}
		
		
		
	}
	
	private static Collection<Sequence> readSequencesAsUpperCase(String fastaFile) throws IOException {
		FastaSequenceIO fsio = new FastaSequenceIO(fastaFile);
		Collection<Sequence> seqs = fsio.loadAll();
		for(Sequence seq : seqs) {
			seq.setSequenceBases(seq.getSequenceBases().toUpperCase());
		}
		return seqs;
	}
	
	/**
	 * @param args
	 * @throws IOException 
	 */
	public static void main(String[] args) throws IOException {
		
		CommandLineParser p = new CommandLineParser();
		p.addBooleanArg("-d", "Debug logging on", false, false);
		p.addStringArg("-f", "Fasta file of sequences", true);
		p.addStringArg("-e", "File containing list of possible restriction enzymes, one per line (options: " + RestrictionEnzymeFactory.RestrictionEnzymeName.commaSeparatedList() + ")", true);
		p.addIntArg("-s", "Oligo size including primers, etc.", false, DEFAULT_OLIGO_SIZE);
		p.addIntArg("-v", "Overlap size for Gibson assembly", false, DEFAULT_OVERLAP_SIZE);
		p.addIntArg("-p", "Primer length", false, DEFAULT_PRIMER_SIZE);
		p.addStringArg("-o", "Output file prefix", true);
		p.addStringArg("-p3", "Primer3core executable", true);
		p.parse(args);
		if(p.getBooleanArg("-d")) {
			logger.setLevel(Level.DEBUG);
		}
		Collection<TypeIISRestrictionEnzyme> enzymes = RestrictionEnzymeFactory.readFromFile(p.getStringArg("-e"));
		int oligoSize = p.getIntArg("-s");
		int overlapSize = p.getIntArg("-v");
		int primerLength = p.getIntArg("-p");
		String outPrefix = p.getStringArg("-o");
		String primer3core = p.getStringArg("-p3");
		Collection<Sequence> seqs = readSequencesAsUpperCase(p.getStringArg("-f"));
		GibsonAssemblyOligoPool g = new GibsonAssemblyOligoPool(seqs, enzymes, oligoSize, overlapSize, primerLength, primer3core);
		g.designAndWriteOutput(outPrefix);
		
		logger.info("");
		logger.info("All done.");

	}

}
