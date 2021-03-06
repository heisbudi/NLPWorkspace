// -*- tab-width: 4 -*-
//Title:        JET
//Copyright:    2005
//Author:       Ralph Grishman
//Description:  A Java-based Information Extraction Toolkit
//              (ACE extensions)

package AceJet;

import java.util.*;
import java.io.*;
import Jet.Tipster.*;
import Jet.Parser.*;
import Jet.Control;
import Jet.JetTest;
import Jet.Pat.Pat;
import Jet.Refres.Resolve;

import opennlp.maxent.*;
import opennlp.maxent.io.*;


/**
 *  assigns ACE events to a Document, given the entities, times, and values.
 */

public class EventTagger {

	// parameters
	//  use statistical model to gather arguments
	static boolean useArgumentModel = true;
	//  events below this probability are dropped
	static double EVENT_PROBABILITY_THRESHOLD = 0.80;
	//  args below this prob are never added to an event, and not used
	//  in estimating event probability
	static double MIN_ARG_PROBABILITY = 0.10;
	//  args below this probability are dropped from the final event
	static double ARGUMENT_PROBABILITY_THRESHOLD = 0.35;
	// minimal confidence for an arg to be used for coref determination
	static double COREF_CONFIDENCE = 0.10;
	// minimal probability for merging two events
	static double COREF_THRESHOLD = 0.40;

	// mapping from an anchor to a list of EventPatterns
	TreeMap anchorMap;

	PrintStream eventFeatureWriter;
	// PrintStream evTypeFeatureWriter;
	PrintStream argFeatureWriter;
	PrintStream roleFeatureWriter;
	PrintStream corefFeatureWriter;

	static GISModel eventModel;
	// static GISModel evTypeModel;
	static GISModel argModel;
	static GISModel roleModel;
	static GISModel corefModel;

	static final String home =
	    "C:/Documents and Settings/Ralph Grishman/My Documents/";
	static final String ace =
			home + "Ace 05/V4/";
	static final String aceModels =
			// home + "ace 05/models/robust/";
			// home + "ace 05/models/non-robust/";
			// home + "Ace 05/models/no-sense/"; // final 2005 eval set
			// home + "ace 05/models/sense/";
			// home + "ace 05/models/noPA/";        // no PNB
			// home + "ace 05/models/noNB/";        // no NB
			// home + "ace 05/models/chunk/";       // chunk (no parse)
			// home + "ace 05/models/noGLARF/";        // relations from parse (no GLARF)
			// home + "Ace 05/models/Dec4/";
			// --- split (chunk / syntax / GLARF) patterns
			// with parse and GLARF
			// home + "Ace 05/models/splitPatterns/";
			// with chunk and GLARF
			// home + "Ace 05/models/chunkGLARF/";
			// with just chunk
			home + "Ace 05/models/newChunk/";
	static String triplesDir =
			// home + "Ace 05/eval/tuples/";  // eval files
			// ace + "Nov1405-ntuples/";      // no-sense tuples
			// xxx ace + "Nov1105-ntuples/";  // non-robust tuples
			// xxx ace + "Nov1205-ntuples/";  // robust tuples
			// ace + "Dec4-stuples/";         // new Charniak parser
			// ace + "010306-slow-tuples/";
			ace + "011306-fast-tuples/";    // new Charniak parser, fast mode
	static String triplesSuffix =
			// ".sent.txt.ace-n-tuple90";
			// ".sent.txt.ns-ace-n-tuple90";
			// ".sent.txt.ns-2005-ace-n-tuple90";
			// ".sent.txt.ns-2005a-ace-n-tuple92";
			".sent.txt.ns-2005-fast-ace-n-tuple92";
			// ".sgm.sent.ns-2005-fast-ace-n-tuple92"; // for GALE files

	// use predicate-argument roles from GLARF
	static boolean usePA = true;
	// use parser (pre-parsed text), else use chunker
	static boolean useParser = true;
	static boolean processOnlyOddDocuments = false;
	static boolean processOnlyEvenDocuments = false;

	public EventTagger () {
		anchorMap = new TreeMap();
	}

	/**
	 *  trains an event tagger from a set of text and APF files.
	 *  @param fileList  a list of text file names, one per line.
	 *                   The APF file names are obtained by replacing
	 *                   'sgm' by 'apf.xml'.
	 */

	public void train (String fileList, int pass) throws IOException {
		BufferedReader reader = new BufferedReader (new FileReader(fileList));
		int docCount = 0;
		String currentDocPath;
		while ((currentDocPath = reader.readLine()) != null) {
			docCount++;
			// if (docCount > 10) break;
			if (processOnlyOddDocuments && docCount%2 == 0) continue;
			if (processOnlyEvenDocuments && docCount%2 == 1) continue;
			System.out.println ("\nProcessing file " + currentDocPath);
		  String textFile = ace + (useParser ? "perfect-parses/" : "") + currentDocPath;
			String xmlFile = ace + currentDocPath.replaceFirst(".sgm", ".apf.xml");
			String docId = currentDocPath.replaceFirst(".sgm","");
			ExternalDocument doc = new ExternalDocument("sgml", textFile);
			doc.setAllTags(true);
			doc.open();
			doc.stretchAll();
			Resolve.ACE = true;
			Control.processDocument (doc, null, false, 0);
			AceDocument aceDoc = new AceDocument(textFile, xmlFile);
			if (pass == 0)
				acquirePatterns (doc, aceDoc, docId);
			else if (pass == 1)
				evaluatePatterns (doc, aceDoc, docId);
			else if (pass == 2)
				trainEventModel (doc, aceDoc, docId);
			else /* pass == 3 */
				trainCorefModel (doc, aceDoc, docId);
		}
		reader.close();
	}

	String[] patternTypeList = {"CHUNK", "SYNTAX", "PA"};

	/**
	 *  trains the tagger from document 'doc' and corresponding AceDocument
	 *  (APF file) aceDoc.
	 */

	public void acquirePatterns (Document doc, AceDocument aceDoc, String docId) {
		SyntacticRelationSet relations = new SyntacticRelationSet();
		if (usePA) {
			relations.readRelations (triplesDir + docId + triplesSuffix);
		} else {
			relations.addRelations(doc);
		}
		ArrayList events = aceDoc.events;
		for (int i=0; i<events.size(); i++) {
			AceEvent event = (AceEvent) events.get(i);
			ArrayList mentions = event.mentions;
			for (int j=0; j<mentions.size(); j++) {
				AceEventMention m = (AceEventMention) mentions.get(j);
				System.out.println ("\nProcessing mention " + m.id + " = " + m.text);
				// .. get anchor extent and text
				Span anchorExtent = m.anchorJetExtent;
				String anchor =
					EventPattern.normalizedAnchor (anchorExtent, m.anchorText, doc, relations);
				// generate patterns
				for (String patternType : patternTypeList) {
					EventPattern ep = new EventPattern (patternType, doc, relations, event, m);
					if (ep.empty()) continue;
					System.out.println (patternType + " pattern = " + ep);
					addPattern (anchor, ep);
					// try event pattern
					AceEvent builtEvent = ep.match(anchorExtent, anchor, doc, relations, aceDoc);
					if (builtEvent == null)
						System.err.println ("**** match failed ****");
					// else
					 	// System.out.println ("Reconstructed event = " + builtEvent);
					// prepare training data for argument classifier
				}
				if (trainArgModels) {
					trainArgClassifier (event, m, doc, aceDoc, relations);
				}
			}
		}
	}

	private void addPattern (String anchor, EventPattern ep) {
		List patternList = (List) anchorMap.get(anchor);
		if (patternList == null) {
			patternList = new ArrayList();
			anchorMap.put(anchor, patternList);
		}
		if (!patternList.contains(ep))
			patternList.add(ep);
		// --
		String relatedForm = (String) SyntacticRelationSet.nomVmap.get(anchor);
		if (relatedForm != null) {
			EventPattern epClone = new EventPattern(ep);
			epClone.anchor = relatedForm;
			epClone.paths = null;
			patternList = (List) anchorMap.get(relatedForm);
			if (patternList == null) {
				patternList = new ArrayList();
				anchorMap.put(relatedForm, patternList);
			}
			if (!patternList.contains(epClone))
				patternList.add(epClone);
		}
		// ---
	}

	private void addBasicPattern (String anchor, EventPattern ep) {
		List patternList = (List) anchorMap.get(anchor);
		if (patternList == null) {
			patternList = new ArrayList();
			anchorMap.put(anchor, patternList);
		}
		if (!patternList.contains(ep))
			patternList.add(ep);
	}

	/**
	 *  trains two statistical models:
	 *  - argModel to decide whether a mention is an argument of an event
	 *  - roleModel to decide which role the mention should have
	 */

	private void trainArgClassifier (AceEvent event, AceEventMention eventMention,
			Document doc, AceDocument aceDoc, SyntacticRelationSet relations) {
		// get anchor
		Span anchorExtent = eventMention.anchorJetExtent;
		String anchorText = eventMention.anchorText;
		String anchor = EventPattern.normalizedAnchor (anchorExtent, anchorText, doc, relations);
		AceMention anchorMention =
			new AceEventAnchor (anchorExtent, anchorExtent, anchorText, doc);
		// find sentence containing anchor
		Annotation sentence = findContainingSentence (doc, anchorExtent);
		if (sentence == null) return;
		Span sentenceSpan = sentence.span();
		// iterate over mentions in sentence
		ArrayList mentions = aceDoc.getAllMentions();
		for (int im=0; im<mentions.size(); im++) {
			AceMention mention = (AceMention) mentions.get(im);
			if (!mention.jetExtent.within(sentenceSpan)) continue;
		// - compute syntactic path
		// - determine if mention has role in event
		  ArrayList arguments = eventMention.arguments;
		  String role = "noArg";
		  for (int ia=0; ia<arguments.size(); ia++) {
		  	AceEventMentionArgument argument = (AceEventMentionArgument) arguments.get(ia);
		  	if (argument.value.equals(mention)) {
		  		role = argument.role;
		  		break;
		  	}
		  }
		  Datum d = argumentFeatures (doc, anchor, event, mention, anchorMention, relations);
			//   outcome = argument role
			if (role == "noArg") {
				d.setOutcome("noArg");
				argFeatureWriter.println(d.toString());
			} else {
				d.setOutcome("arg");
				argFeatureWriter.println(d.toString());
				d.setOutcome (role);
				roleFeatureWriter.println(d.toString());
			}
		}
	}

	private Datum argumentFeatures
	  (Document doc, String anchor, AceEvent event, AceMention mention,
	   AceMention anchorMention, SyntacticRelationSet relations) {
  	String direction;
		ChunkPath cpath;
		// - compute chunk path from anchor
		if (anchorMention.getJetHead().start() < mention.getJetHead().start()) {
			direction = "follow";
			cpath = new ChunkPath (doc, anchorMention, mention);
		} else {
			direction = "precede";
			cpath = new ChunkPath (doc, mention, anchorMention);
		}
		String spath = EventSyntacticPattern.buildSyntacticPath
		  (anchorMention.getJetHead().start(), mention.getJetHead().start(), relations);
		// System.out.println ("spath = " + spath);
		//   build feature entry
		Datum d = new Datum();
		//   = anchor word
		d.addFV ("anchor", anchor);
		//   = event type
		d.addFV ("evType", event.type);
		//   = EDT type of mention
		d.addFV ("menType", mention.getType());
		//   = head of mention (by itself and coupled with event subtype)
		//     (intuition:  US attacks Iraq, bombers kill, etc.)
		String headText = Resolve.normalizeName(mention.getHeadText()).replace(' ','_');
		d.addFV ("arg", headText);
		d.addFV ("evTypeArg", event.subtype + ":" + headText);
		//   = word preceding mention
		int pos = mention.jetExtent.start();
		Annotation token = doc.tokenEndingAt(pos);
		if (token != null) {
			d.addFV ("prevToken", doc.text(token).trim());
			d.addFV ("prevTokenAndType", event.type + "_" + doc.text(token).trim());
		}
		//   = chunk path, direction, distance
		if (cpath == null || cpath.toString() == null)
			d.addFV ("noChunkPath", null);
		else {
			String cpathString = cpath.toString().replace(' ','_');
			d.addFV ("chunkPath", direction + "_" + cpathString);
			d.addFV ("chunkPathAndType", event.type + "_" + direction + "_" + cpathString);
			d.addFV ("dist", Integer.toString(cpath.size()));
		}
		//   = syntactic path
		if (spath == null)
			d.addF ("noSynPath");
		else {
			d.addFV ("synPath", spath);
			d.addFV ("synPathEvType", event.type + "_" + spath);
			d.addFV ("synPathTypes", event.type + "_" + mention.getType() + "_" + spath);
		}
		return d;
	}

	private Annotation findContainingSentence (Document doc, Span span) {
		Vector sentences = doc.annotationsOfType ("sentence");
		if (sentences == null) {
			System.err.println ("findContainingSentence:  no sentences found");
			return null;
		}
		Annotation sentence = null;
		for (int i=0; i<sentences.size(); i++) {
			Annotation s = (Annotation) sentences.get(i);
			if (span.within(s.span())) {
				sentence = s;
				break;
			}
		}
		if (sentence == null) {
			System.err.println ("findContainingSentence:  can't find sentence with span");
			return null;
		}
		return sentence;
	}

	static private void buildClassifierModel (String featureFileName, String modelFileName) {
    boolean USE_SMOOTHING = false;
    boolean PRINT_MESSAGES = true;
    double SMOOTHING_OBSERVATION = 0.1;
		try {
	    FileReader datafr = new FileReader(new File(featureFileName));
	    EventStream es =
				new BasicEventStream(new PlainTextByLineDataStream(datafr));
	    GIS.SMOOTHING_OBSERVATION = SMOOTHING_OBSERVATION;
	    GISModel model = GIS.trainModel(es, 100, 4, USE_SMOOTHING, PRINT_MESSAGES);

	    File outputFile = new File(modelFileName);
	    GISModelWriter writer = new SuffixSensitiveGISModelWriter(model, outputFile);
	    writer.persist();
		} catch (Exception e) {
			System.err.print("Unable to create model due to exception: ");
			System.err.println(e);
		}
   }

	private static GISModel loadClassifierModel (String modelFileName) {
		try {
			File f = new File(modelFileName);
			GISModel m = new SuffixSensitiveGISModelReader(f).getModel();
			System.err.println ("GIS model " + f.getName() + " loaded.");
		  return m;
		} catch (Exception e) {
		  e.printStackTrace();
		  System.exit(0);
		  return null; // required by compiler
		}
	}

	/**
	 *  add arguments to 'event' using classifiers to decide if a mention is
	 *  an argument, and to assign that argument a role.
	 */

	private void collectArguments (AceEvent event, AceEventMention eventMention,
			Document doc, AceDocument aceDoc, SyntacticRelationSet relations) {
		// get anchor
		Span anchorExtent = eventMention.anchorJetExtent;
		String anchorText = eventMention.anchorText;
		String anchor = EventPattern.normalizedAnchor (anchorExtent, anchorText, doc, relations);
		AceMention anchorMention = new AceEventAnchor (anchorExtent,
		  	  eventMention.anchorJetExtent, anchorText, doc);
		// identify roles already filled
		Set rolesFilled = rolesFilledInEvent(event);
		// find sentence containing anchor
		Annotation sentence = findContainingSentence (doc, anchorExtent);
		if (sentence == null) return;
		Span sentenceSpan = sentence.span();
		// get arguments already used
		HashSet argumentsUsed = argumentValues(event);
		// iterate over mentions in sentence
		HashMap bestRoleProb = new HashMap();
		HashMap bestRoleFiller = new HashMap();
		ArrayList mentions = aceDoc.getAllMentions();
		for (int im=0; im<mentions.size(); im++) {
			AceMention mention = (AceMention) mentions.get(im);
			if (!mention.jetExtent.within(sentenceSpan)) continue;
			if (mention.getJetHead().within(anchorExtent)) continue; // Nov. 4 2005
			//   build feature entry
			Datum d = argumentFeatures (doc, anchor, event, mention, anchorMention, relations);
			//   classify:
			//      probability that this is an argument
			//      most likely role assignment
			double argProb = argModel.eval(d.toArray())[argModel.getIndex("arg")];
			String role = roleModel.getBestOutcome(roleModel.eval(d.toArray())).intern();
			// System.out.println ("argProb of " + mention.getHeadText() + " is " + argProb);
			/*  the following code chooses the best valid role
			double[]  roleProbs = roleModel.eval(d.toArray());
			String role = null;
			double best = -1;
			for (int i=0; i<roleModel.getNumOutcomes(); i++) {
				String r = roleModel.getOutcome(i);
				if (roleProbs[i] > best
				    && AceEventArgument.isValid(event.subtype, r, mention)) {
					role = r;
					best = roleProbs[i];
				}
			}
			if (role == null) continue; */
			//      if role already filled, continue
			if (rolesFilled.contains(role)) continue;
			//      if not a valid role for this event type, continue
			if (!AceEventArgument.isValid(event.subtype, role, mention)) continue;
			//      if likely argument, add to event
			if (argProb > MIN_ARG_PROBABILITY) {
				// if this mention has the highest probability of filling this role,
				//    record it
				if (bestRoleProb.get(role) == null ||
				    argProb > ((Double) bestRoleProb.get(role)).doubleValue()) {
					bestRoleProb.put(role, new Double(argProb));
					bestRoleFiller.put(role, mention);
				}
			}
		}
		Iterator roleIt = bestRoleFiller.keySet().iterator();
		while (roleIt.hasNext()) {
			String role = (String) roleIt.next();
			AceMention mention = (AceMention) bestRoleFiller.get(role);
			// don't use an argument twice
			AceEventArgumentValue argValue = mention.getParent();
			if (argumentsUsed.contains(argValue)) continue;
			double argProb = ((Double) bestRoleProb.get(role)).doubleValue();
			AceEventMentionArgument mentionArg =
				new AceEventMentionArgument(mention, role);
			mentionArg.confidence = argProb;
			eventMention.arguments.add(mentionArg);
			AceEventArgument eventArg = new AceEventArgument(argValue, role);
			eventArg.confidence = argProb;
			event.arguments.add(eventArg);
			// System.out.println ("Adding " + mention.getHeadText() + " in role " +
			//                      role + " with prob " + argProb);
			argumentsUsed.add(argValue);
		}
	}

	private static boolean evalTrace = false;
	private static int eventWeight = 10;

	/**
	 *  applies the learned patterns to Document 'doc' and records the
	 *  number of times it produced a correct or incorrect event.
	 */

	public void evaluatePatterns (Document doc, AceDocument aceDoc, String docId) {
		SyntacticRelationSet relations = new SyntacticRelationSet();
		if (usePA) {
			relations.readRelations (triplesDir + docId + triplesSuffix);
		} else {
			relations.addRelations(doc);
		}
		ArrayList events = aceDoc.events;
		Vector constituents = doc.annotationsOfType("constit");
		for (int i=0; i<constituents.size(); i++) {
			Annotation constit = (Annotation) constituents.get(i);
			String cat = (String) constit.get("cat");
			if (cat == "n" || cat == "v" || cat == "tv" || cat == "ven" ||
				  cat == "ving" || cat == "adj") {
				String anchor = EventPattern.normalizedAnchor (constit, doc, relations);
				Span anchorExtent = constit.span();
				List patterns = (List) anchorMap.get(anchor);
				if (patterns != null) {
					String eventType = null;
					// record success / failure for individual patterns
					for (int j=0; j<patterns.size(); j++) {
						EventPattern ep = (EventPattern) patterns.get(j);
						AceEvent event = ep.match(anchorExtent, anchor, doc, relations, aceDoc);
						if (event != null) {
							if (evalTrace) System.out.println ("Evaluating " + ep);
							if (evalTrace) System.out.println ("  for matched event " + event);
							AceEventMention mention = (AceEventMention) event.mentions.get(0);
							if (evalTrace) System.out.println ("  with extent " + doc.text(mention.jetExtent));
							AceEvent keyEvent = correctEvent(anchorExtent, event, events);
							if (keyEvent != null) {
								// if event is correct, count correct and spurious arguments
								ArrayList arguments = mention.arguments;
								ArrayList keyArguments = correctEventMention.arguments;
								ArrayList correctArguments = new ArrayList(arguments);
								correctArguments.retainAll(keyArguments);
								ArrayList spuriousArguments = new ArrayList(arguments);
								spuriousArguments.removeAll(keyArguments);
								int successCount = eventWeight + correctArguments.size();
								int failureCount = spuriousArguments.size();
								// ---
								ep.evaluation.recordSuccess(mention.arguments, successCount);
								ep.evaluation.recordFailure(mention.arguments, failureCount);
								if (evalTrace) System.out.println ("    a success");
								eventType = event.type + ":" + event.subtype;
							} else {
								// if event is incorrect, count all arguments as spurious
								int failureCount = eventWeight + mention.arguments.size();
								ep.evaluation.recordFailure(mention.arguments, failureCount);
								if (evalTrace) System.out.println ("    a failure");
							}
						}
					}
				}
			}
		}
	}

	AceEventMention correctEventMention = null;

	/**
	 *  if 'event', triggered by the text in 'anchorExtent'
	 *  (a Jet span), matches one of the events in 'keyEvents', returns
	 *  the event (in keyEvents), else null.
	 */

	private AceEvent correctEvent (Span anchorExtent, AceEvent event, ArrayList keyEvents) {
		AceEventMention mention = (AceEventMention) event.mentions.get(0);
		for (int i=0; i<keyEvents.size(); i++) {
			AceEvent keyEvent = (AceEvent) keyEvents.get(i);
			ArrayList keyMentions = keyEvent.mentions;
			for (int j=0; j<keyMentions.size(); j++) {
				AceEventMention keyMention = (AceEventMention) keyMentions.get(j);
				Span keyExtent = keyMention.jetExtent;
				if (anchorExtent.within(keyExtent) &&
				    event.type.equals(keyEvent.type) &&
				    event.subtype.equals(keyEvent.subtype)) {
				  correctEventMention = keyMention;
					return keyEvent;
				}
			}
		}
		return null;
	}

	private void trainEventModel (Document doc, AceDocument aceDoc, String docId) {
		SyntacticRelationSet relations = new SyntacticRelationSet();
		if (usePA) {
			relations.readRelations (triplesDir + docId + triplesSuffix);
		} else {
			relations.addRelations(doc);
		}
		ArrayList events = aceDoc.events;
		// iterate over potential anchors
		Vector constituents = doc.annotationsOfType("constit");
		for (int i=0; i<constituents.size(); i++) {
			Annotation constit = (Annotation) constituents.get(i);
			String cat = (String) constit.get("cat");
			if (cat == "n" || cat == "v" || cat == "tv" || cat == "ven" ||
				  cat == "ving" || cat == "adj") {
				String anchor = EventPattern.normalizedAnchor (constit, doc, relations);
				Span anchorExtent = constit.span();
				List patterns = (List) anchorMap.get(anchor);
				if (patterns == null) continue;
				CONFIDENT_ARG = 0.10;
				AceEvent event = eventAnchoredByConstituent
				  (constit, doc, aceDoc, docId, relations, 0);
				// if no event (success count too low), skip for now
				if (event == null) continue;
				// if a very confident arg (pattern match), skip for now
				if (confidentRoleCount (event, 0.999) != 0) continue;
				EventPattern pattern = (EventPattern) patternMatched;
				// outcome:
				//    is this the anchor of a true event (even if of different type)
				Datum d = eventFeatures (anchor, event, pattern);
				boolean isEvent = correctEvent(anchorExtent, event, events) != null;
				d.setOutcome(isEvent ? "event" : "noEvent");
				eventFeatureWriter.println(d.toString());
			}
		}
	}

	/**
	 *  the features for deciding whether an event is reportable are
	 *  - the anchor
	 *  - the fraction of times the anchor is reportable
	 *  - the probability that it has each argument
	 */

	private Datum eventFeatures (String anchor, AceEvent event, EventPattern pattern) {
		Datum d = new Datum();
		d.addF (anchor);
		PatternEvaluation eval = pattern.evaluation;
		int successRate = 5 * eval.successCount / (eval.successCount + eval.failureCount);
		d.addFV ("successRate", Integer.toString(successRate));
		ArrayList arguments = event.arguments;
		for (int i=0; i<arguments.size(); i++) {
			AceEventArgument arg = (AceEventArgument) arguments.get(i);
			String role = arg.role;
			int conf = (int) (arg.confidence * 5.);
			d.addF (role + "+" + conf);
	 	}
		return d;
	}

	private void trainCorefModel (Document doc, AceDocument aceDoc, String docId) {
		SyntacticRelationSet relations = new SyntacticRelationSet();
		if (usePA) {
			relations.readRelations (triplesDir + docId + triplesSuffix);
		} else {
			relations.addRelations(doc);
		}
		// event list from key
		ArrayList events = aceDoc.events;
		// system-generated event list
		ArrayList systemEvents = new ArrayList();
		//
		HashMap keyIdToSystemEventMap = new HashMap();
		int aceEventNo = 1;
		// iterate over potential anchors
		Vector constituents = doc.annotationsOfType("constit");
		for (int i=0; i<constituents.size(); i++) {
			Annotation constit = (Annotation) constituents.get(i);
			String cat = (String) constit.get("cat");
			if (cat == "n" || cat == "v" || cat == "tv" || cat == "ven" ||
				  cat == "ving" || cat == "adj") {
				String anchor = EventPattern.normalizedAnchor (constit, doc, relations);
				Span anchorExtent = constit.span();
				AceEvent event = eventAnchoredByConstituent
				  (constit, doc, aceDoc, docId, relations, aceEventNo);
				event = pruneEvent (event, constit, doc, relations);
				if (event != null) {
					AceEvent keyEvent = correctEvent(anchorExtent, event, events);
					// if not a correct event, continue
					if (keyEvent == null) continue;
					String keyEventId = keyEvent.id;
					// determine which system event it should be folded into, if any
					Integer I = (Integer) keyIdToSystemEventMap.get(keyEventId);
					int systemEventIndex = (I == null) ? -1 : I.intValue();
					  System.out.println ("systemEventIndex = " + systemEventIndex);
					// compare to all events in systemEvents
					for (int iEvent = 0; iEvent < systemEvents.size(); iEvent++) {
						AceEvent priorEvent = (AceEvent) systemEvents.get(iEvent);
						if (!priorEvent.subtype.equals(event.subtype)) continue;
						// if same type/subtype, generate training example
						// (with outcome = whether it belongs to this systemEvent)
						Datum d = corefFeatures (priorEvent, event, anchor);
						d.setOutcome((iEvent == systemEventIndex) ? "merge" : "dontMerge");
						corefFeatureWriter.println(d.toString());
					}
					// if it should be folded in, do so; else create new event
					if (systemEventIndex >= 0) {
						// fold event into prior event
						AceEvent priorEvent = (AceEvent) systemEvents.get(systemEventIndex);
						priorEvent.arguments = mergeArguments (event.arguments, priorEvent.arguments);
						AceEventMention m = (AceEventMention) event.mentions.get(0);
						priorEvent.addMention(m);
						m.setId(priorEvent.id + "-" + priorEvent.mentions.size());
					} else {
						systemEvents.add(event);
						keyIdToSystemEventMap.put(keyEventId, new Integer(systemEvents.size()-1));
						aceEventNo++;
					}
				}
			}
		}
	}

	private Datum corefFeatures (AceEvent priorEvent, AceEvent event, String anchor) {
		Datum d = new Datum();
		// type/subtype of events
		d.addFV ("subtype", event.subtype);
		// normalized anchor
		d.addFV ("anchor", anchor);
		// nominal anchor
		if (anchor.endsWith("/n")) d.addF("nomAnchor");
		// distance (100's of chars, up to 10)
		AceEventMention lastMentionPriorEvent =
			(AceEventMention) priorEvent.mentions.get(priorEvent.mentions.size() -1);
		int posnPriorEvent = lastMentionPriorEvent.anchorExtent.start();
		AceEventMention lastMentionOfEvent =
			(AceEventMention) event.mentions.get(event.mentions.size() -1);
		int posnEvent = lastMentionOfEvent.anchorExtent.start();
		int distance = posnEvent - posnPriorEvent;
		d.addFV ("distance", Integer.toString(Math.min(distance /100, 9)));
		// matching anchors
		if (lastMentionOfEvent.anchorText.equals(lastMentionPriorEvent.anchorText))
			d.addF("anchorMatch");
		// overlapping and conflicting roles with confidence
		ArrayList priorArgs = priorEvent.arguments;
		ArrayList args = event.arguments;
		for (int i=0; i<priorArgs.size(); i++) {
			AceEventArgument arg1 = (AceEventArgument) priorArgs.get(i);
			if (arg1.confidence < COREF_CONFIDENCE) continue;
			String role1 = arg1.role;
			String id1 = arg1.value.id;
			for (int j=0; j<args.size(); j++) {
				AceEventArgument arg2 = (AceEventArgument) args.get(j);
				if (arg2.confidence < COREF_CONFIDENCE) continue;
				String role2 = arg2.role;
				String id2 = arg2.value.id;
				int confidence = (int) (Math.min(arg1.confidence, arg2.confidence) * 5);
				if (role1.equals(role2))
					if (id1.equals(id2))
						d.addFV ("overlap", role1 + ":" + confidence);
					else
						d.addFV ("conflict", role1 + ":" + confidence);
			}
		}
		// determiner of noun anchor
		return d;
	}

	// minimum confidence to consider arg a confident argument
	private static double CONFIDENT_ARG = 0.20;

	/**
	 *  identify ACE events in Document 'doc' and add them to 'aceDoc'.
	 */

	public void tag (Document doc, AceDocument aceDoc, String currentDocPath, String docId) {
		SyntacticRelationSet relations = new SyntacticRelationSet();
		if (usePA) {
			relations.readRelations (triplesDir + currentDocPath + triplesSuffix);
		} else {
			relations.addRelations(doc);
		}
		int aceEventNo = 1;
		Vector constituents = doc.annotationsOfType("constit");
		HashSet matchedAnchors = new HashSet();
		if (constituents != null) {
			for (int i=0; i<constituents.size(); i++) {
				Annotation constit = (Annotation) constituents.get(i);
				String cat = (String) constit.get("cat");
				if (cat == "n" || cat == "v" || cat == "tv" || cat == "ven" ||
				    cat == "ving" || cat == "adj") {
				  Span anchorExtent = constit.span();
				  if (matchedAnchors.contains(anchorExtent)) continue; //<< added 13 Feb 06
					AceEvent event = eventAnchoredByConstituent
					  (constit, doc, aceDoc, docId, relations, aceEventNo);
					event = pruneEvent (event, constit, doc, relations);
					if (event != null) {
						aceDoc.addEvent (event);
						aceEventNo++;
						matchedAnchors.add(anchorExtent);
					}
				}
			}
		}
		eventCoref (aceDoc, doc, relations);
	}

	/**
	 *  if 'constit' is the anchor of an event, return the event.
	 */

	AceEvent eventAnchoredByConstituent
		(Annotation constit, Document doc, AceDocument aceDoc, String docId,
		 SyntacticRelationSet relations, int aceEventNo) {
		String anchor = EventPattern.normalizedAnchor (constit, doc, relations);
		Span anchorExtent = constit.span();
		// search patterns for match
		List patterns = (List) anchorMap.get(anchor);
		if (patterns == null)
			return null;
		AceEvent bestEvent =
			matchPatternSet (patterns, anchorExtent, anchor, doc, relations, aceDoc);
		if (bestEvent == null)
			return null;
		Annotation sentence = EventPattern.containingSentence(doc, anchorExtent);
		int slash = docId.lastIndexOf('/');
			if (slash >= 0)
				docId = docId.substring(slash + 1);
		String eventId = docId + "-EV" + aceEventNo;
		bestEvent.setId(eventId);
		AceEventMention bestMention = (AceEventMention) bestEvent.mentions.get(0);
		bestMention.setId(eventId + "-1");
		// collect additional arguments using statistical model
		if (useArgumentModel)
			collectArguments (bestEvent, bestMention, doc, aceDoc, relations);
		if (confidentRoleCount (bestEvent, CONFIDENT_ARG) > 0) {
			// System.out.println ("Tagged event = " + bestEvent);
			return bestEvent;
		} else {
			return null;
		}
	}

	AceEvent pruneEvent (AceEvent event, Annotation constit, Document doc,
			SyntacticRelationSet relations) {
		if (event == null)
			return null;
		if (confidentRoleCount (event, 0.999) != 0)
			return event;
		String anchor = EventPattern.normalizedAnchor (constit, doc, relations);
		EventPattern pattern = (EventPattern) patternMatched;
		Datum d = eventFeatures (anchor, event, pattern);
		double eventProb = eventModel.eval(d.toArray())[eventModel.getIndex("event")];
		if (eventProb < EVENT_PROBABILITY_THRESHOLD)
			return null;
		// System.out.println ("Accepted event with prob " + eventProb);
		ArrayList args = event.arguments;
		Iterator it = args.iterator();
		while (it.hasNext()) {
			AceEventArgument ea = (AceEventArgument) it.next();
			if (ea.confidence * eventProb < ARGUMENT_PROBABILITY_THRESHOLD) it.remove();
		}
		args = ((AceEventMention)event.mentions.get(0)).arguments;
		it = args.iterator();
		while (it.hasNext()) {
			AceEventMentionArgument ea = (AceEventMentionArgument) it.next();
			if (ea.confidence * eventProb < ARGUMENT_PROBABILITY_THRESHOLD) it.remove();
		}
		return event;
	}

	EventPattern patternMatched;

	AceEvent matchPatternSet (List patterns, Span anchorExtent, String anchor,
		  Document doc, SyntacticRelationSet relations, AceDocument aceDoc) {
		patternMatched = null;
		AceEvent bestEvent = null;
		EventPattern bestPattern = null;
		int bestMatchScore = 0;
		for (int j=0; j<patterns.size(); j++) {
			EventPattern ep = (EventPattern) patterns.get(j);
			// if (ep.patternType.equals("PA")) continue; // <<< SYNTAX only
			// try event pattern
			AceEvent event = ep.match(anchorExtent, anchor, doc, relations, aceDoc);
			// if it matches
			if (event != null && ep.evaluation.test(event.arguments) > 0) {
			  // and fills some argument role
			  int score = ep.getMatchScore() + ep.evaluation.test(event.arguments);
			  if (ep.patternType.equals("SYNTAX")) score += 50; //<<<<<<<< favor SYNTAX over PA
			  if (event.arguments.size() >= 0 && score > bestMatchScore) {
					bestMatchScore = score;
					bestEvent = event;
					bestPattern = ep;
				}
			}
		}
		patternMatched = bestPattern;
		// if (bestMatchScore > 0) System.out.println ("Best match score = " + bestMatchScore);
		return bestEvent;
	}

	/**
	 *  returns the set of argument roles filled in 'event'.
	 */

	private HashSet rolesFilledInEvent (AceEvent event) {
		ArrayList args = event.arguments;
		HashSet roles = new HashSet();
		for (int i=0; i<args.size(); i++) {
			AceEventArgument arg = (AceEventArgument) args.get(i);
			roles.add(arg.role);
		}
		return roles;
	}

	/**
	 *  returns a count of the number of arguments whose confidence
	 *  is above a threshold.
	 */

	private int confidentRoleCount (AceEvent event, double threshold) {
		ArrayList args = event.arguments;
		int count = 0;
		for (int i=0; i<args.size(); i++) {
			AceEventArgument arg = (AceEventArgument) args.get(i);
			if (arg.confidence > threshold) count++;
		}
		return count;
	}

	/**
	 *  returns a set of the values of the arguments of the event.
	 */

	private HashSet argumentValues (AceEvent event) {
		ArrayList args = event.arguments;
		HashSet values = new HashSet();
		for (int i=0; i<args.size(); i++) {
			AceEventArgument arg = (AceEventArgument) args.get(i);
			values.add(arg.value);
		}
		return values;
	}

	public void tag (String fileList) throws IOException {
		BufferedReader reader = new BufferedReader (new FileReader(fileList));
		int docCount = 0;
		String currentDocPath;
		while ((currentDocPath = reader.readLine()) != null) {
			System.out.println ("\nProcessing file " + currentDocPath);
		  // String textFile = ace + currentDocPath;
		  String textFile = ace + (useParser ? "perfect-parses/" : "") + currentDocPath;
			String xmlFile = ace + currentDocPath.replaceFirst(".sgm", ".apf.xml");
			String outputFile = ace	 + "output/" + currentDocPath.replaceFirst(".sgm", ".apf");
			// for evaluation --------------
			/*
			textFile = home + "Ace 05/eval/allparses/" + currentDocPath;
			xmlFile = home + "Ace 05/eval/ACE05_diagdata_v1/english/" +
			                        currentDocPath.replaceFirst(".sgm", ".entities.apf.xml");
			outputFile = home + "Ace 05/eval/output/" +
			                        currentDocPath.replaceFirst(".sgm", ".apf.xml");
			*/
			// ------------
			ExternalDocument doc = new ExternalDocument("sgml", textFile);
			doc.setAllTags(true);
			doc.open();
			doc.stretchAll();
			Control.processDocument (doc, null, false, 0);
			AceDocument aceDoc = new AceDocument(textFile, xmlFile);
			aceDoc.events.clear();
			tag (doc, aceDoc, currentDocPath.replaceFirst(".sgm",""), aceDoc.docID);
			aceDoc.write(new PrintWriter (new FileWriter (outputFile)));
		}
	}

	/**
	 *  performs coreference on the events in an Ace document.  On entry, the
	 *  AceDocument <CODE>aceDoc</CODE> should have a set of events each with a
	 *  single mention.  The event mentions which are believed to corefer are
	 *  combined into a single event.
	 */

	public void eventCoref (AceDocument aceDoc, Document doc, SyntacticRelationSet relations) {
		ArrayList events = aceDoc.events;
		System.out.println ("eventCoref: " + events.size() + " event mentions");
		ArrayList newEvents = new ArrayList();
		nextevent:
		for (int i=0; i<events.size(); i++) {
			AceEvent event = (AceEvent) events.get(i);
			// is there a prior event on newEvents of the same type
			// such that the arguments are compatible?
			int priorEventIndex =  -1;
			double priorEventProb = 0.;
			for (int j=newEvents.size()-1; j>=0; j--) {
				AceEvent newEvent = (AceEvent) newEvents.get(j);
				if (event.type.equals(newEvent.type) &&
				    event.subtype.equals(newEvent.subtype)) {
				  AceEventMention m = (AceEventMention) event.mentions.get(0);
		    	String anchor =
		    		EventPattern.normalizedAnchor (m.anchorExtent, m.anchorText, doc, relations);
				  Datum d = corefFeatures (newEvent, event, anchor);
				  double prob = corefModel.eval(d.toArray())[corefModel.getIndex("merge")];
					if (prob > COREF_THRESHOLD && prob > priorEventProb) {
						priorEventIndex = j;
						priorEventProb = prob;
					}
				}
			}
			if (priorEventIndex >= 0) {
				AceEvent priorEvent = (AceEvent) newEvents.get(priorEventIndex);
				priorEvent.arguments = mergeArguments (event.arguments, priorEvent.arguments);
				AceEventMention m = (AceEventMention) event.mentions.get(0);
				priorEvent.addMention(m);
				//     fix id for new mention
				m.setId(priorEvent.id + "-" + priorEvent.mentions.size());
			} else {
				// if not, put event on newEvents
				newEvents.add(event);
			}
		}
		aceDoc.events = newEvents;
		System.out.println ("eventCoref: " + events.size() + " events");
	}

	private boolean compatibleArguments (ArrayList args1, ArrayList args2) {
		boolean intersect = false;
		for (int i=0; i<args1.size(); i++) {
			AceEventArgument arg1 = (AceEventArgument) args1.get(i);
			if (arg1.confidence < COREF_CONFIDENCE) continue;
			String role1 = arg1.role;
			String id1 = arg1.value.id;
			for (int j=0; j<args2.size(); j++) {
				AceEventArgument arg2 = (AceEventArgument) args2.get(j);
				if (arg2.confidence < COREF_CONFIDENCE) continue;
				String role2 = arg2.role;
				String id2 = arg2.value.id;
				if (role1.equals(role2))
					if (id1.equals(id2))
						intersect = true;
					else
						return false;
			}
		}
		return intersect;
	}

	private ArrayList mergeArguments (ArrayList args1, ArrayList args2) {
		ArrayList result = new ArrayList(args1);
		nextarg:
		for (int i=0; i<args2.size(); i++) {
			AceEventArgument arg2 = (AceEventArgument) args2.get(i);
			String role2 = arg2.role;
			String id2 = arg2.value.id;
			for (int j=0; j<args1.size(); j++) {
				AceEventArgument arg1 = (AceEventArgument) args1.get(j);
				String role1 = arg1.role;
				String id1 = arg1.value.id;
				if (role1.equals(role2) && id1.equals(id2))
					continue nextarg;
			}
			result.add(arg2);
		}
		return result;
	}

	public void report (String reportFile) throws IOException {
		PrintWriter reportWriter = new PrintWriter (new FileWriter (reportFile));
		Set anchors = anchorMap.keySet();
		Iterator iter = anchors.iterator();
		while (iter.hasNext()) {
			String anchor = (String) iter.next();
			reportWriter.println("\n" + anchor + " ================================");
			List patterns = (List) anchorMap.get(anchor);
			for (int j=0; j<patterns.size(); j++) {
				EventPattern ep = (EventPattern) patterns.get(j);
				reportWriter.println(ep.toString());
			}
		}
		reportWriter.close();
	}

	/**
	 *  write out all the patterns to file 'fileName' in a form which
	 *  can be reloaded by the 'load' method.
	 */

	public void save (String fileName) throws IOException {
		PrintWriter writer = new PrintWriter (new FileWriter (fileName));
		Set anchors = anchorMap.keySet();
		Iterator iter = anchors.iterator();
		while (iter.hasNext()) {
			String anchor = (String) iter.next();
			List patterns = (List) anchorMap.get(anchor);
			for (int j=0; j<patterns.size(); j++) {
				EventPattern ep = (EventPattern) patterns.get(j);
				ep.write(writer);
			}
		}
		writer.close();
	}

	/**
	 *  load an event pattern set in the form saved by the 'save' method.
	 */

	public void load (String fileName) throws IOException {
		BufferedReader reader = new BufferedReader (new FileReader (fileName));
		int patternCount = 0;
		String line = reader.readLine();
		while (line != null) {
			EventPattern ep = new EventPattern(reader);
			addBasicPattern (ep.anchor, ep);
			patternCount++;
			line = reader.readLine();
		}
		System.out.println (patternCount + " patterns loaded");
	}

	static final String fileListTrain1 = ace + "perfect-parses/nw-tail.txt";
	static final String fileListTrain2 = ace + "perfect-parses/bn-tail.txt";
	static final String fileListTrain3 = ace + "perfect-parses/bc-tail.txt";
	static final String fileListTrain4 = ace + "perfect-parses/wl-tail.txt";
	static final String fileListTrain5 = ace + "perfect-parses/cts-tail.txt";
	static final String fileListTrain6 = ace + "perfect-parses/un-tail.txt";
	// static final String fileListTest = ace + "perfect-parses/nw-head.txt";
	// static final String fileListTest = ace + "perfect-parses/bn-head.txt";
	// static final String fileListTest = ace + "perfect-parses/bc-head.txt";
	// static final String fileListTest = ace + "perfect-parses/un-head.txt";
	static final String fileListTest = ace + "perfect-parses/head6.txt";
	// ---- for eval
	// static final String fileListTest = home + "Ace 05/eval/evalFiles";

	static final String eventFeatureFileName = aceModels + "eventFeatureFile.log";
	static final String eventModelFileName = aceModels + "eventModel.log";
	// static final String evTypeFeatureFileName = ace + "evTypeFeatureFile.log";
	// static final String evTypeModelFileName = ace + "evTypeModel.log";

	static final String corefFeatureFileName = aceModels + "corefFeatureFile.log";
	static final String corefModelFileName = aceModels + "corefModel.log";

	static final String argFeatureFileName = aceModels + "argFeatureFile.log";
	static final String argModelFileName = aceModels + "argModel.log";
	static final String roleFeatureFileName = aceModels + "roleFeatureFile.log";
	static final String roleModelFileName = aceModels + "roleModel.log";

	static final String argFeatureHalfFileName = aceModels + "argFeatureHalfFile.log";
	static final String argModelHalfFileName = aceModels + "argModelHalf.log";
	static final String roleFeatureHalfFileName = aceModels + "roleFeatureHalfFile.log";
	static final String roleModelHalfFileName = aceModels + "roleModelHalf.log";

	static final String eventPatternFile = aceModels + "eventPatterns.log";
	static final String eventReportFile = aceModels + "eventPatternReport.log";
	static final String halfEventPatternFile = aceModels + "eventPatternsHalf.log";
	static final boolean savePatterns = true;
	static final boolean trainArgModels = true;

	static final boolean trainPatterns = false;
	static final boolean trainEvModel = false;
	static final boolean trainCorefModel = false;
	static final boolean evaluate = true;

	public static void main (String[] args) throws IOException {
		if (useParser)
			JetTest.initializeFromConfig("props/ace use parses.properties");
		else
			JetTest.initializeFromConfig("props/ME ace 05.properties");
		Ace.gazetteer = new Gazetteer();
		Ace.gazetteer.load("data/loc.dict");
		Pat.trace = false;
		Resolve.trace = false;
		AceDocument.ace2005 = true;
		EventTagger et = new EventTagger();
		if (trainPatterns) {
			if (trainArgModels) {
				et.argFeatureWriter = new PrintStream (new FileOutputStream (argFeatureFileName));
				et.roleFeatureWriter = new PrintStream (new FileOutputStream (roleFeatureFileName));
			}
			for (int pass=0; pass<=1; pass++) {
				et.train (fileListTrain1, pass);
				et.train (fileListTrain2, pass);
				et.train (fileListTrain3, pass);
				et.train (fileListTrain4, pass);
				et.train (fileListTrain5, pass);
				et.train (fileListTrain6, pass);
			}
			et.report (eventReportFile);
			if (trainArgModels) {
				et.argFeatureWriter.close();
				et.roleFeatureWriter.close();
				buildClassifierModel(argFeatureFileName, argModelFileName);
				buildClassifierModel(roleFeatureFileName, roleModelFileName);
			}
			if (savePatterns)
				et.save (eventPatternFile);
		} else if (evaluate) {
			et.load (eventPatternFile);
		}
		if (trainEvModel) {
			trainEventModel (et);
		}
		if (trainCorefModel) {
			trainCorefModel (et);
		}
		if (evaluate) {
			evaluateEventTagger (et);
		}
	}

	private static void trainEventModel (EventTagger et) throws IOException {
		processOnlyOddDocuments = true;
		// set arg models
		et.argFeatureWriter = new PrintStream (new FileOutputStream (argFeatureHalfFileName));
		et.roleFeatureWriter = new PrintStream (new FileOutputStream (roleFeatureHalfFileName));
	  // train patterns on odd documents
	  for (int pass=0; pass<=1; pass++) {
			et.train (fileListTrain1, pass);
			et.train (fileListTrain2, pass);
			et.train (fileListTrain3, pass);
			et.train (fileListTrain4, pass);
			et.train (fileListTrain5, pass);
			et.train (fileListTrain6, pass);
		}
		et.save (halfEventPatternFile);
		// build arg models (half) and load them
		et.argFeatureWriter.close();
		et.roleFeatureWriter.close();
		buildClassifierModel(argFeatureHalfFileName, argModelHalfFileName);
		buildClassifierModel(roleFeatureHalfFileName, roleModelHalfFileName);
		argModel = loadClassifierModel(argModelHalfFileName);
		roleModel = loadClassifierModel(roleModelHalfFileName);
		// set ev model
		et.eventFeatureWriter = new PrintStream (new FileOutputStream (eventFeatureFileName));
		// et.evTypeFeatureWriter = new PrintStream (new FileOutputStream (evTypeFeatureFileName));
		// train event model on even documents
		processOnlyOddDocuments = false;
		processOnlyEvenDocuments = true;
		et.train (fileListTrain1, 2);
		et.train (fileListTrain2, 2);
		et.train (fileListTrain3, 2);
		et.train (fileListTrain4, 2);
		et.train (fileListTrain5, 2);
		et.train (fileListTrain6, 2);
		// build ev model
		et.eventFeatureWriter.close();
		// et.evTypeFeatureWriter.close();
		buildClassifierModel(eventFeatureFileName, eventModelFileName);
		// buildClassifierModel(evTypeFeatureFileName, evTypeModelFileName);
	}

	private static void trainCorefModel (EventTagger et) throws IOException {
		argModel = loadClassifierModel(argModelHalfFileName);
		roleModel = loadClassifierModel(roleModelHalfFileName);
		eventModel = loadClassifierModel(eventModelFileName);
		et.load (halfEventPatternFile);
		// set coref model
		et.corefFeatureWriter = new PrintStream (new FileOutputStream (corefFeatureFileName));
		// train coref model on even documents, using models from odd documents
		processOnlyEvenDocuments = true;
		et.train (fileListTrain1, 3);
		et.train (fileListTrain2, 3);
		et.train (fileListTrain3, 3); /*
		et.train (fileListTrain4, 3);
		et.train (fileListTrain5, 3);
		et.train (fileListTrain6, 3);  */
		// build coref model
		et.corefFeatureWriter.close();
		buildClassifierModel(corefFeatureFileName, corefModelFileName);
	}

	private static void evaluateEventTagger (EventTagger et)  throws IOException {
		et.loadAllModels (aceModels);
		// et.assessPatterns (fileListTest);
		et.tag (fileListTest);
	}

	public void loadAllModels (String modelDir) throws IOException {
		eventModel = loadClassifierModel(modelDir + "eventModel.log");
		// evTypeModel = loadClassifierModel(evTypeModelFileName);
		argModel = loadClassifierModel(modelDir + "argModel.log");
		roleModel = loadClassifierModel(modelDir + "roleModel.log");
		corefModel = loadClassifierModel(modelDir + "corefModel.log");
	}

}
