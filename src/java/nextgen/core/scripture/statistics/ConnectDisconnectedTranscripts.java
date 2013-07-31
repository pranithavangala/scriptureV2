package nextgen.core.scripture.statistics;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import nextgen.core.alignment.AbstractPairedEndAlignment.TranscriptionRead;
import nextgen.core.alignment.Alignment;
import nextgen.core.annotation.Annotation;
import nextgen.core.annotation.BasicAnnotation;
import nextgen.core.annotation.Gene;
import nextgen.core.coordinatesystem.TranscriptomeSpace;
import nextgen.core.general.CloseableFilterIterator;
import nextgen.core.model.AlignmentModel;
import nextgen.core.readFilters.PairedAndProperFilter;
import nextgen.core.readFilters.SameOrientationFilter;
import nextgen.core.readFilters.SplicedReadFilter;
import nextgen.core.scripture.BuildScriptureCoordinateSpace;
import org.apache.commons.collections15.Predicate;
import org.apache.log4j.Logger;
import org.broad.igv.Globals;

import broad.core.datastructures.IntervalTree;
import broad.core.datastructures.Pair;
import broad.core.error.ParseException;
import broad.core.util.CLUtil;
import broad.core.util.CLUtil.ArgumentMap;
import broad.pda.annotation.BEDFileParser;

public class ConnectDisconnectedTranscripts {
	
	static Logger logger = Logger.getLogger(ConnectDisconnectedTranscripts.class.getName());
	Map<String,Collection<Gene>> annotations;
	private AlignmentModel model;
	private int constant = 10000;
	private double medianInsertSize=300;
	private static int DEFAULT_INSERT_SIZE = 500;
	private static int DEFAULT_NUM_BINS = 100;
	
	static final String usage = "Usage: ConnectDisconnectedTranscripts -task doWork "+
			"\n**************************************************************"+
			"\n\t\tArguments"+
			"\n**************************************************************"+
			"\n\t\t-out <Output file [Defaults to stdout]> "+
			"\n\t\t-in <Reconstruction bed file. [BED by default]> "+
			"\n\t\t-alignment <Alignment bam file with data on which reconstructions were calculated> "+
			"\n\t\t-strand <first/second : mate in the direction of transcription> "+
			"\n\t\t-maxSize <Maximum insert size. Default=500bp> "+
			"\n";

	public ConnectDisconnectedTranscripts(String annotationFile,String outputName,File bamFile,TranscriptionRead strand,int maxSize) throws IOException{
		
		annotations= BEDFileParser.loadDataByChr(new File(annotationFile));
		model=new AlignmentModel(bamFile.getAbsolutePath(), null, new ArrayList<Predicate<Alignment>>(),true,strand,false);
		model.addFilter(new PairedAndProperFilter());
		
		//Compute insert size distribution
		logger.info("Compute insert size distribution");
		//Default number of bins =10
		medianInsertSize = model.getReadSizeDistribution(new TranscriptomeSpace(annotations), maxSize, DEFAULT_NUM_BINS).getMedianOfAllDataValues();
		//medianInsertSize = 600;
		logger.info("Median size = "+medianInsertSize);
		doWork(outputName);
	}
	
	
	public void doWork(String outputName) throws IOException{
	
		Map<String,Collection<Gene>> conn = new HashMap<String,Collection<Gene>>();
		for(String chr:annotations.keySet()){
			//Set<Gene> considered = new HashSet<Gene>();
			//For all genes on this chromosome
			logger.info("Processing "+chr);
			Collection<Gene> newGenes = new TreeSet<Gene>();
			//MAKE AN INTERVAL TREE OF THE GENES on this chr
			IntervalTree<Gene> tree = new IntervalTree<Gene>();
			for(Gene g:annotations.get(chr)){
				tree.put(g.getStart(), g.getEnd(), g);
			}
			//For each transcript
			//Iterate over all assemblies
			Iterator<Gene> iter=tree.toCollection().iterator();
			while(iter.hasNext()){
				Gene gene=iter.next();
				//For all assemblies downstream of this assembly in 10kB regions
				Iterator<Gene> overlappers=tree.overlappingValueIterator(gene.getEnd(), gene.getEnd()+constant);
				
				newGenes.add(gene);
				while(overlappers.hasNext()){
					Gene other = overlappers.next();
					if(isCandidate(gene,other)){
						if(pairedEndReadSpansTranscripts(gene, other)){ 
							if(secondTranscriptIsSingleExon(gene,other)){
								/*if(!considered.contains(gene)){
									considered.add(gene);
								}
								if(!considered.contains(other)){
									considered.add(other);
								}*/
								logger.info("The genes "+gene.getName()+" "+gene.toUCSC()+" and "+other.getName()+" "+other.toUCSC()+" must be connected.");
								
								//Connect the genes
								Annotation connected = getConnectedTranscript(gene,other);
								if(connected!=null){
									newGenes.remove(gene);
									newGenes.add(new Gene(connected));
								}
							}
						}
						else{
							//logger.info("The genes "+gene.getName()+" "+gene.toUCSC()+" and "+other.getName()+" "+other.toUCSC()+" do not have paired reads");
						}
					}
				}				
			}
		}
		BuildScriptureCoordinateSpace.write(outputName+".connected.bed",annotations);
	}
	
	/**
	 * This function will return true if gene and other have at least 1 paired end read in common
	 * @param gene
	 * @param other
	 * @return
	 */
	private boolean pairedEndReadSpansTranscripts(Gene gene,Gene other){
		
		boolean rtrn = false;
		//Get all overlapping paired end reads in same orientation as the gene
		CloseableFilterIterator<Alignment> iter = new CloseableFilterIterator<Alignment>(model.getOverlappingReads(new BasicAnnotation(gene.getChr(),gene.getStart(),other.getEnd()),true), new SameOrientationFilter(gene));
		while(iter.hasNext()){
			Alignment read = iter.next();
			List<Annotation> mates = (List<Annotation>) read.getReadAlignments(model.getCoordinateSpace());
			if(mates.get(0).getOrientation().equals(gene.getOrientation()) && mates.get(1).getOrientation().equals(gene.getOrientation())){
				if((gene.contains(mates.get(0)) && other.contains(mates.get(1)))
						||
						gene.contains(mates.get(1)) && other.contains(mates.get(0))){
					rtrn = true;
					break;
				}
			}
			
		}
		iter.close();
		return rtrn;
	}
	
	/**
	 * Returns true if the transcript at the oriented 3' end is a single exon transcript.
	 * @param gene
	 * @param other
	 * @return
	 */
	private boolean secondTranscriptIsSingleExon(Gene gene,Gene other){
		
		Pair<Gene> orderedGenes = ConnectDisconnectedTranscripts.getOrderedAssembly(gene, other);
		if(gene.isNegativeStrand()){
			if(orderedGenes.getValue1().getBlocks().size()==1){
				return true;
			}
		}
		else{
			if(orderedGenes.getValue2().getBlocks().size()==1){
				return true;
			}
		}
		return false;
	}
	
	/**
	 * Connect the two genes by fusing the last exon of the first and the first exon of the last gene
	 * @param gene
	 * @param other
	 * @return
	 * @throws IOException 
	 */
	private Annotation getConnectedTranscript(Gene gene,Gene other) throws IOException{
		
		Pair<Gene> orderedGenes = ConnectDisconnectedTranscripts.getOrderedAssembly(gene, other);
		Annotation connected = null;
		/*
		 * If the distance between the transcripts is less than the insert size,
		 * paste through
		 */
		if(orderedGenes.getValue2().getStart()-orderedGenes.getValue1().getEnd() < medianInsertSize){
//			List<Annotation> blocks = new ArrayList<Annotation>();
			
//			Annotation tojoin = null;
			// getBlocks for first gene
/*			for(Annotation b:orderedGenes.getValue1().getBlocks()){
				//LAST EXON
				if(b.equals(orderedGenes.getValue1().getLastExon())){
					tojoin = b;
				}
				else
					blocks.add(b);
			}
			// getBlocks for second gene
			for(Annotation b:orderedGenes.getValue2().getBlocks()){
				//FIRST EXON
				if(b.equals(orderedGenes.getValue2().getFirstExon())){
					tojoin.setEnd(b.getEnd());
				}
				else
					blocks.add(b);
			}
			connected = new Gene(gene.getChr(), gene.getName()+"_"+other.getName(), gene.getOrientation(), blocks);*/
			
			Gene firstGene = orderedGenes.getValue1().copy();
			firstGene.setEnd(orderedGenes.getValue2().getStart());
			connected = firstGene.union(orderedGenes.getValue2());
		}
		/*
		 * If the distance between the transcripts is more than the insert size,
		 * 		if there is at least 1 splice read spanning the junction,
		 * 			union it
		 */
		else{
			boolean flag= false;
			Annotation junction = orderedGenes.getValue1().getLastExon().union(orderedGenes.getValue2().getFirstExon());
			CloseableFilterIterator<Alignment> splicedIter=new CloseableFilterIterator<Alignment>(model.getOverlappingReads(junction,false), new SplicedReadFilter());
			while(splicedIter.hasNext()){
				Alignment read = splicedIter.next();
				//if there is at least 1 splice read spanning the junction,
				if(BuildScriptureCoordinateSpace.compatible(read,junction)){
					flag=true;
					break;
				}
			}
			splicedIter.close();
			if(flag){
				connected = gene.union(other);				
			}
			/*
			 * Else NO CONNECTION
			 */
		}
		return connected;		
	}
	
	public static Pair<Gene> getOrderedAssembly(Gene gene1,Gene gene2) {
		Pair<Gene> rtrn=new Pair<Gene>();
		//Order by CompareTo
		if(gene1.compareTo(gene2)<0){
			rtrn.setValue1(gene1);
			rtrn.setValue2(gene2);
		}
		else{
			rtrn.setValue1(gene2);
			rtrn.setValue2(gene1);
		}
		return rtrn;
	}
	/**
	 * This function returns true if
	 * 		1. gene and other do not overlap
	 * 	    2. gene and other are in the same orientation
	 * @param gene
	 * @param other
	 * @return
	 */
	private boolean isCandidate(Gene gene,Gene other){ 
		
		if(gene.overlaps(other)){
			//logger.info(gene.getName()+" overlaps "+gene.toUCSC());
			return false;
		}
		else{
			//if transcript is in an intron of the other transcript
			if(gene.getEnd()>other.getStart() && other.getEnd()>gene.getStart()){
				//logger.info("One is inside the intron of the other");
				return false;
			}
			if(gene.getOrientation().equals(other.getOrientation())){
				//logger.info("The orientation is same");
				return true;
			}
		}
		//logger.info("Something else");
		return false;
	}
	
	public static void main (String [] args) throws ParseException, IOException {
		
		/*
		 * Gives a log4j error. Check later.
		 */
		Globals.setHeadless(true);
		/*
		 * @param for ArgumentMap - size, usage, default task
		 * argMap maps the command line arguments to the respective parameters
		 */
		ArgumentMap argMap = CLUtil.getParameters(args,usage,"doWork");
		TranscriptionRead strand = TranscriptionRead.UNSTRANDED;
		if(argMap.get("strand").equalsIgnoreCase("first")){
			//System.out.println("First read");
			strand = TranscriptionRead.FIRST_OF_PAIR;
		}
		else if(argMap.get("strand").equalsIgnoreCase("second")){
			//System.out.println("Second read");
			strand = TranscriptionRead.SECOND_OF_PAIR;
		}
		else
			System.out.println("no strand");
		
		
		new ConnectDisconnectedTranscripts(argMap.getInput(),argMap.getOutput(),new File(argMap.getMandatory("alignment")),strand,argMap.getInteger("maxSize", DEFAULT_INSERT_SIZE));
		
	}
}