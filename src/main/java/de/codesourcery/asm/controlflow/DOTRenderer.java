/**
 * Copyright 2012 Tobias Gierke <tobias.gierke@code-sourcery.de>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.codesourcery.asm.controlflow;

import java.util.HashMap;
import java.util.Map;
import java.util.Vector;

import de.codesourcery.asm.controlflow.Edge.EdgeType;

/**
 * Crude DOT (graphviz) renderer to control-flow graphs.
 * 
 * @author tobias.gierke@code-sourcery.de
 * 
 * @see ControlFlowGraph
 * @see ControlFlowAnalyzer
 */
public class DOTRenderer
{
	
	public String render_internal_function(ControlFlowGraph graph,Map<String,ControlFlowGraph> m,String prefix, Map<String,String> oldfunr){
		 final StringBuilder result = new StringBuilder( "" );
	        @SuppressWarnings("unchecked")
			Map<String, String> funrendered = (Map<String, String>) (((HashMap) (oldfunr)).clone());
	        Vector<String> functoadd=new Vector<>();
	        // enumerate vertices
	        String name = graph.getMethod().name;
	        for ( IBlock block : graph.getAllNodes() ) 
	        {
	            String label;
	            String shape="ellipse";
	            if ( block instanceof MethodEntry ) {
	                label = name + "_entry";
	            } else if ( block instanceof MethodExit ) {
	                label = name + "_exit";
	            } 
	            else 
	            {
	                if ( block.isVirtual( graph.getMethod() ) ) {
	                    continue;
	                }
	                label = block.disassemble( graph.getMethod() , false , true ).replace("\n" , "\\l").replace("\"" , "\\\"");
	                if(label.contains("INVOKEVIRTUAL")) {
	                	String[] tmp = label.split("INVOKEVIRTUAL");
	                	for(int i=1; i< tmp.length;i++){
	                		String str = "";
	                		str = tmp[i].substring(1, tmp[i].indexOf('('));
	                		if(m.containsKey(str)) {    
	                			functoadd.addElement(addInQuote(block.getId(),prefix)+"%"+str);
	                		}
	                	}
	                }
	                label += "\\l";
	                shape="box";
	            }
	            shape = "shape="+shape;
	            label="label=\""+label+"\"";
	            if ( block.getId() == null ) {
	                throw new IllegalArgumentException("Block "+block+" has no ID?");
	            }
	            result.append( "    "+mangleNodeName( addInQuote( block.getId(),prefix) )+" ["+shape+","+label+"]\n" );
	        }
	        
	        // enumerate edges
	        for ( IBlock block : graph.getAllNodes() ) 
	        {
	            if ( block instanceof MethodEntry || block instanceof MethodExit || ! block.isVirtual( graph.getMethod() ) ) 
	            {
	                for ( Edge edge: block.getEdges() ) 
	                {
	                    if ( edge.isSuccessor( block ) ) 
	                    {
	                        final IBlock succ = edge.dst;
	                        String style = "";
	                        if ( edge.hasType( EdgeType.CAUGHT_EXCEPTION ) ) // exception
	                        {
	                            String type =(String) edge.metaData;
	                            if ( type == null ) {
	                                type = "ANY";
	                            } else {
	                                type = type.replace("/",".");
	                                if ( type.startsWith("java.lang." ) ) {
	                                    type = type.substring("java.lang.".length() );
	                                }
	                            }
	                            style="[style=dotted,label=\"ex: "+type+"\"]";
	                        } else if ( edge.hasType( EdgeType.TABLE_SWITCH) || edge.hasType( EdgeType.LOOKUP_SWITCH ) ) { // lookup/table switch
	                            Integer key =(Integer) edge.metaData;
	                            String color="";
	                            if ( edge.hasType( EdgeType.LOOKUP_SWITCH ) ) {
	                                color ="color=red,";
	                            }
	                            style="[style=dashed,"+color+"label=\"case: "+key+"\"]";                            
	                        } else if ( edge.metaData != null ) {
	                            style="[label=\""+edge.metaData+"\"]";     
	                        }
	                        result.append( "    "+mangleNodeName( addInQuote(block.getId(),prefix) )+" -> "+mangleNodeName( addInQuote( succ.getId(),prefix) )+" "+style+"\n" );
	                    }
	                }
	            } 
	        }
	        funrendered.put(name,prefix);
	        for(int i=0;i<functoadd.size();i++){
	        	if(functoadd.elementAt(i).split("%")[1].equals(graph.getMethod().name)) {
	        		result.append( "    "+ mangleNodeName(functoadd.elementAt(i).split("%")[0])+" -> " + "\"" + prefix + "START\"" +"\n" );
	        		break;
	        	}
	        	String tmp = funrendered.get(functoadd.elementAt(i).split("%")[1]);
	        	if(tmp != null) {
	        		result.append( "    "+ mangleNodeName(functoadd.elementAt(i).split("%")[0]) + " -> \""+ tmp + "START\"\n" );
	        		result.append( "    "+ "\""+ tmp + "END\"" + " -> " + mangleNodeName(functoadd.elementAt(i).split("%")[0]));
	        		break;
	        	}
	        	result.append(render_internal_function(m.get(functoadd.elementAt(i).split("%")[1]), m,prefix+i+"_",funrendered));
	        	result.append( "    "+ mangleNodeName(functoadd.elementAt(i).split("%")[0]) + " -> \""+ prefix+ i +"_"+"START\"\n" );
	        	result.append( "    "+ "\""+prefix+ i+"_"+"END\"" +" -> "+ mangleNodeName(functoadd.elementAt(i).split("%")[0]) +"\n" );
	        }
	        return result.toString();
	}
	
    public String render(ControlFlowGraph graph,Map<String,ControlFlowGraph> m) 
    {
        final StringBuilder result = new StringBuilder( "digraph \""+mangleNodeName( graph.getMethod().name )+"()\" {\n" );
        Map<String, String> funrendered = new HashMap<>();
        Vector<String> functoadd=new Vector<>();
        String name =  graph.getMethod().name;
        // enumerate vertices
        for ( IBlock block : graph.getAllNodes() ) 
        {
            String label;
            String shape="ellipse";
            if ( block instanceof MethodEntry ) {
                label = name + "_entry";
            } else if ( block instanceof MethodExit ) {
                label = name + "_exit";
            } 
            else 
            {
                if ( block.isVirtual( graph.getMethod() ) ) {
                    continue;
                }
                label = block.disassemble( graph.getMethod() , false , true ).replace("\n" , "\\l").replace("\"" , "\\\"");
                if(label.contains("INVOKEVIRTUAL")) {
                	String[] tmp = label.split("INVOKEVIRTUAL");

                	for(int i=1; i< tmp.length;i++){
                		String str = "";
                		str = tmp[i].substring(1, tmp[i].indexOf('('));
                		if(m.containsKey(str)) {    
                			functoadd.addElement(block.getId()+"%"+str);
                		}
                	}
                }
                label += "\\l";
                shape="box";
            }
            shape = "shape="+shape;
            label="label=\""+label+"\"";
            if ( block.getId() == null ) {
                throw new IllegalArgumentException("Block "+block+" has no ID?");
            }
            result.append( "    "+mangleNodeName( block.getId() )+" ["+shape+","+label+"]\n" );
        }
        
        // enumerate edges
        for ( IBlock block : graph.getAllNodes() ) 
        {
            if ( block instanceof MethodEntry || block instanceof MethodExit || ! block.isVirtual( graph.getMethod() ) ) 
            {
                for ( Edge edge: block.getEdges() ) 
                {
                    if ( edge.isSuccessor( block ) ) 
                    {
                        final IBlock succ = edge.dst;
                        String style = "";
                        if ( edge.hasType( EdgeType.CAUGHT_EXCEPTION ) ) // exception
                        {
                            String type =(String) edge.metaData;
                            if ( type == null ) {
                                type = "ANY";
                            } else {
                                type = type.replace("/",".");
                                if ( type.startsWith("java.lang." ) ) {
                                    type = type.substring("java.lang.".length() );
                                }
                            }
                            style="[style=dotted,label=\"ex: "+type+"\"]";
                        } else if ( edge.hasType( EdgeType.TABLE_SWITCH) || edge.hasType( EdgeType.LOOKUP_SWITCH ) ) { // lookup/table switch
                            Integer key =(Integer) edge.metaData;
                            String color="";
                            if ( edge.hasType( EdgeType.LOOKUP_SWITCH ) ) {
                                color ="color=red,";
                            }
                            style="[style=dashed,"+color+"label=\"case: "+key+"\"]";                            
                        } else if ( edge.metaData != null ) {
                            style="[label=\""+edge.metaData+"\"]";     
                        }
                        result.append( "    "+mangleNodeName( block.getId() )+" -> "+mangleNodeName( succ.getId() )+" "+style+"\n" );
                    }
                }
            } 
        }

        funrendered.put(name,"");
        for(int i=0;i<functoadd.size();i++){
        	if(functoadd.elementAt(i).split("%")[1].equals(graph.getMethod().name)) {
        		result.append( "    "+ mangleNodeName(functoadd.elementAt(i).split("%")[0])+" -> " + "\""  + "START\"" +"\n" );
        		break;
        	}
        	result.append(render_internal_function(m.get(functoadd.elementAt(i).split("%")[1]), m,i+"_",funrendered));
        	result.append( "    "+ mangleNodeName(functoadd.elementAt(i).split("%")[0]) + " -> \""+ i+"_"+"START\"\n" );
        	result.append( "    "+ "\""+ i+"_"+"END\"" +" -> "+ mangleNodeName(functoadd.elementAt(i).split("%")[0]) +"\n" );
        }
        result.append("}");
        return result.toString();
    }
    
    private static final String mangleNodeName(String id) {
        return id.replace("<", "" ).replace(">", "");
    }
    private static final String addInQuote(String id, String toadd) {
    	if(id.substring(0,1).equals("\""))
    		return id.substring(0,1)+toadd+id.substring(1);
    	else
    		return "\""+toadd+id+"\"";
    }
}