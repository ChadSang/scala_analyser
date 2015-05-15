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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;

import de.codesourcery.asm.util.ASMUtil;
import de.codesourcery.asm.util.ASMUtil.ILogger;

/**
 * Command-line application to generate a control flow graph (in Graphviz DOT format)
 * for a given class. 
 * 
 * <p>Just run this class without any arguments to see the available command-line options.</p>
 * 
 * @author tobias.gierke@code-sourcery.de
 */
public class ControlFlowGrapher
{
    private File[] classPathEntries;
    private boolean verboseOutput = false;
    private Pattern methodNamePattern = null;
    private boolean includeConstructors = false;
    private File outputDir;
    private String classToAnalyze;
    
    private final ControlFlowAnalyzer analyzer = new ControlFlowAnalyzer();

    public static void main(String[] args) throws Exception
    {
        final ControlFlowGrapher main = new ControlFlowGrapher();
        try {
            applyArgs(main , args);
            main.run();
        } 
        catch(Exception e) 
        {
            e.printStackTrace();
            printUsage();
        }
    }

    private static void printUsage() {
        System.out.println("\n\nUsage: [-debug] [-v] [-constructors] [-search <classpath entries>] [-match <regex>] -dir <directory> <CLASS NAME>\n\n"+
                "[-debug] => enable debug output\n"+
                "[-v] => enable verbose output\n"+
                "[-search <classpath entries> => Substitute for JVM -classpath option since that one does not work with self-executable JARs\n"+
                "-dir <directory> => outputs .dot files to this directory\n"+
                "[-constructors] => include constructors in flow analysis\n"+
                "[-match <regex>] => only analyze methods whose name matches this regex\n"+
                "<CLASS NAME> => name of class to analyze\n\n");
    }

    private static void applyArgs(ControlFlowGrapher main, String[] args) throws Exception
    {
        for ( int i = 0 ; i < args.length ; i++) 
        {
            final String arg = args[i];

            try 
            {
                switch( arg ) 
                {
                    case "-v":
                        main.verboseOutput = true;
                        break;
                    case "-dir":
                        main.outputDir = new File( args[i+1 ] );
                        i++;
                        break;
                    case "-search": // hack for http://bugs.sun.com/view_bug.do?bug_id=4459663
                        final String[] pathEntries = args[i+1].split("\\:");
                        main.classPathEntries = new File[ pathEntries.length ];
                        int j = 0;
                        for ( String path : pathEntries ) {
                            main.classPathEntries[j++] = new File(path);
                        }
                        break;
                    case "-debug":
                        main.analyzer.setDebug( true );
                        break;
                    case "-constructors":
                        main.includeConstructors = true;
                        break;
                    case "-match":
                        main.methodNamePattern = Pattern.compile( args[i+1] );
                        i++;
                        break;
                    default:
                        main.classToAnalyze = arg;
                }
            } 
            catch(ArrayIndexOutOfBoundsException e) 
            {
                throw new RuntimeException("Syntax error, failed to access required parameters for option '"+arg+"'",e);
            }
        }
    }
    
    public void showclass (String classname) throws AnalyzerException, IOException {
        final ClassReader classReader = ASMUtil.createClassReader( classname , classPathEntries , new ILogger() {

            @Override
            public void logVerbose(String msg)
            {
                ControlFlowGrapher.this.logVerbose( msg );
            }
        });
        final ClassNode cn = new ClassNode();
        classReader.accept( cn , 0 );
    	for ( Object m : cn.methods ) 
        {
            final MethodNode mn= (MethodNode) m; 
            if ( isConstructor( mn ) ) 
            {
                if ( includeConstructors ) {
                    visitMethod( mn , classname );
                }
            } else {
                if ( matches( mn ) ) {
                    visitMethod( mn  , classname );
                } else {
                    logVerbose("Ignored method: "+mn.name+"_"+mn.desc);
                }
            }
        }
        //System.out.println(analyzer.graphmap);

        
    /*    for ( Object m : cn.methods ) 
        {
            final MethodNode mn= (MethodNode) m; 
            if ( isConstructor( mn ) ) 
            {
                if ( includeConstructors ) {
                	MethodDOTRender( mn );
                }
            } else {
                if ( matches( mn ) ) {
                	MethodDOTRender( mn );
                } else {
                    logVerbose("Ignored method: "+mn.name+"_"+mn.desc);
                }
            }
        }*/
        for (Object m : cn.innerClasses) {
        	final InnerClassNode icn = (InnerClassNode) m;
        	if(icn.name.equals(classname))
        		break;
        	showclass(icn.name);
    /*        final ClassReader classReader2 = ASMUtil.createClassReader( icn.name , classPathEntries , new ILogger() {

                @Override
                public void logVerbose(String msg)
                {
                    ControlFlowGrapher.this.logVerbose( msg );
                }
            });
            
            final ClassNode cn2 = new ClassNode();
            classReader2.accept( cn2 , 0 );
        	for ( Object m2 : cn2.methods ) 
            {
                final MethodNode mn= (MethodNode) m2; 
                if ( isConstructor( mn ) ) 
                {
                    if ( includeConstructors ) {
                        visitMethod( mn , icn.name );
                    }
                } else {
                    if ( matches( mn ) ) {
                        visitMethod( mn  , icn.name );
                    } else {
                        logVerbose("Ignored method: "+mn.name+"_"+mn.desc);
                    }
                }
            }
            //System.out.println(analyzer.graphmap);

            
            for ( Object m2 : cn2.methods ) 
            {
                final MethodNode mn= (MethodNode) m2; 
                if ( isConstructor( mn ) ) 
                {
                    if ( includeConstructors ) {
                    	MethodDOTRender( mn );
                    }
                } else {
                    if ( matches( mn ) ) {
                    	MethodDOTRender( mn );
                    } else {
                        logVerbose("Ignored method: "+mn.name+"_"+mn.desc);
                    }
                }
            }
            //System.out.println(((InnerClassNode)cn2.innerClasses.get(0)).innerName);
        //    for(Object o : cn2.innerClasses)
          //  	System.out.println(((InnerClassNode)o).innerName);*/
        }
    }

    public void run() throws Exception 
    {
        if ( StringUtils.isBlank( classToAnalyze ) ) {
            throw new IllegalStateException("Class name not set");
        }

        if ( outputDir == null ) {
            throw new IllegalStateException("No output directory set");
        }        

        logVerbose("Output directory: "+outputDir.getAbsolutePath());


        showclass(classToAnalyze);
        
        final ClassReader classReader = ASMUtil.createClassReader( classToAnalyze , classPathEntries , new ILogger() {

            @Override
            public void logVerbose(String msg)
            {
                ControlFlowGrapher.this.logVerbose( msg );
            }
        });
        
        final ClassNode cn = new ClassNode();
        
        classReader.accept( cn , 0 );
        for ( Object m : cn.methods ) 
        {
            final MethodNode mn= (MethodNode) m; 
            if ( isConstructor( mn ) ) 
            {
                if ( includeConstructors ) {
                	MethodDOTRender( mn ,classToAnalyze);
                }
            } else {
                if ( matches( mn ) ) {
                	MethodDOTRender( mn ,classToAnalyze);
                } else {
                    logVerbose("Ignored method: "+mn.name+"_"+mn.desc);
                }
            }
        }
    }
    
    private boolean isConstructor(MethodNode mn) {
        return mn.name.equals("<init>");
    }    
    
    private boolean matches(MethodNode mn) {
        return methodNamePattern == null || methodNamePattern.matcher( mn.name ).matches();
    }

    private void visitMethod(MethodNode method,String owner) throws AnalyzerException, FileNotFoundException 
    {
        final ControlFlowGraph graph = analyzer.analyze(owner,  method );
        
    }
    
    private void MethodDOTRender(MethodNode method,String owner) throws AnalyzerException, FileNotFoundException {
    	ControlFlowGraph graph = analyzer.graphmap.get(formatname(owner)+"#"+method.name);
    	final String dot = new DOTRenderer().render( graph , analyzer.graphmap);

        final File outputFile;
        if ( outputDir != null ) {
            outputFile = new File( outputDir  , toFilename( method )+".dot" );
        } else {
            outputFile = new File( toFilename( method )+".dot" );
        }

        logVerbose("Writing "+outputFile.getAbsolutePath());
        
        if ( ! outputFile.getParentFile().exists() ) {
        	outputFile.getParentFile().mkdirs();
        }
        
        final PrintWriter writer = new PrintWriter( outputFile);
        writer.write( dot );
        writer.close();
    }
    
    private String toFilename(MethodNode method) {
        String result = method.name+"_"+method.desc;
        result = result.replace("<", "");
        result = result.replace(">", "");
        result = result.replace("(","_");
        result = result.replace(")","_");
        result = result.replace("/","_");
        result = result.replace(";","");
        return result;
    }     
    
    private void logVerbose(String s) {
        if ( verboseOutput ) {
            System.out.println( s );
        }
    }
    private static final String formatname(String id) {
        return id.replace(".", "/" );
    }
}