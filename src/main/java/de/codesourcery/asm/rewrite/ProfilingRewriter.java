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
package de.codesourcery.asm.rewrite;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;

import de.codesourcery.asm.controlflow.ControlFlowAnalyzer;
import de.codesourcery.asm.controlflow.ControlFlowGraph;
import de.codesourcery.asm.controlflow.IBlock;
import de.codesourcery.asm.profiling.ExecutionStatistics;
import de.codesourcery.asm.profiling.StatisticsManager;
import de.codesourcery.asm.util.ASMUtil;
import de.codesourcery.asm.util.Disassembler;
import de.codesourcery.asm.util.IClassReaderProvider;
import de.codesourcery.asm.util.IJoinpointFilter;

/**
 * Class transformer that adds bytecode instruction accounting to classes.    
 * 
 * <p>This class uses the {@link ControlFlowAnalyzer} to create a control-flow graph
 * for each method/constructor and at the start of <b>each</b> control block, inserts the following
 * generated byte-code.</p>
 * 
 * <p>A trivial method with only one control block would be rewritten as follows:</p>
 * 
 * <b>BEFORE</b>
 * <pre>
 * public void testMethod() { 
 * }
 * </pre>
 * 
 * <b>AFTER</b>
 * <pre>
 * public void testMethod() { 
 *   final ExecutionStatistics $stat  = StatisticsManager.getStatistics();
 *   $stat.executedInstructionCount += 1; // original method was empty and thus only contained a single RETURN instruction
 *   if ( $stat.executedInstructionCount >= 0 ) {
 *     StatisticsManager.account();
 *   }
 * }
 * </pre>
 * 
 * @author tobias.gierke@code-sourcery.de
 * 
 * @see ControlFlowAnalyzer
 * 
 * @see StatisticsManager
 * @see ExecutionStatistics
 */
public class ProfilingRewriter implements Opcodes
{
    private boolean debug = false;
    private boolean verbose = false;

    public ProfilingRewriter() {
    }

    public static void main(String[] args) throws Exception
    {
        //        final ClassReader classReader = ASMUtil.createClassReader( TestClass.class.getName() , null );
        //        ClassNode cn = new ClassNode();
        //        TraceClassVisitor visitor = new TraceClassVisitor( cn , new ASMifier() , new PrintWriter(System.out) );
        //        classReader.accept( visitor , 0 );
        //        System.out.println("Done.");

        final String clazz = "de.codesourcery.asmtest.TestClass";
        final byte[] newClass = new ProfilingRewriter().rewrite( clazz  ,  null , IJoinpointFilter.ALL );

        final FileOutputStream out = new FileOutputStream( new File("/home/tgierke/tmp/TestClass.class" ) );
        out.write(newClass);
        out.close();

        final MyClassLoader cl = new MyClassLoader();
        final Class<?> generated = cl.defineClass( clazz , newClass );

        final Object instance = generated.newInstance();
        instance.getClass().getMethod("testMethod").invoke( instance );
    }

    protected static class MyClassLoader extends ClassLoader {

        public final Class<?> defineClass(String name, byte[] b) {
            return super.defineClass(name, b, 0 , b.length );
        }
    }
    
    public void setDebugMode(boolean debug)
    {
        this.debug = debug;
    }
    
    public void setVerboseMode(boolean verbose)
    {
        this.verbose = verbose;
    }

    private void logVerbose(String msg) {
        if ( verbose ) {
            System.out.println( msg );
        }
    }

    public byte[] rewrite(final String classToAnalyze, final File[] classPathEntries,IJoinpointFilter filter) throws IOException, AnalyzerException 
    {
        final IClassReaderProvider provider = new IClassReaderProvider() {

            @Override
            public ClassReader getClassReader() throws IOException
            {
                return ASMUtil.createClassReader( classToAnalyze , classPathEntries );
            }

            @Override
            public String getClassName()
            {
                return classToAnalyze;
            }
            
        };
        return rewrite( provider , filter );
    }
    
    @SuppressWarnings("unchecked")
    public byte[] rewrite(IClassReaderProvider provider,IJoinpointFilter filter) throws IOException, AnalyzerException 
    {
        // first pass: create control flow graphs (CFGs) for all methods and constructors
        final String classToAnalyze = provider.getClassName();
        logVerbose("Analyzing "+classToAnalyze+" ... ");

        final ClassNode cn = new ClassNode();
        provider.getClassReader().accept( cn , 0 );

        final ControlFlowAnalyzer analyzer = new ControlFlowAnalyzer();

        final Map<String,ControlFlowGraph> graphs = new HashMap<>();
        for ( MethodNode mn : (List<MethodNode>) cn.methods ) 
        {
            if ( filter.matches( classToAnalyze , mn.name ) ) {
                logVerbose("Analyzing method "+mn.name);
                if ( debug ) {
                	System.out.println( Disassembler.disassemble( mn , true , true ) );
                }
                final ControlFlowGraph graph = analyzer.analyze( classToAnalyze , mn );
                if ( debug ) {
                	System.out.println("Method "+mn.name+"_"+mn.desc+" has the following blocks");
                	for ( IBlock bl : graph.getAllNodes() ) {
                		if ( bl.isVirtual(mn) ) {
                    		System.out.println( bl+" with "+bl.getByteCodeInstructionCount( mn )+" instructions (virtual)");                			
                		} else {
                    		final int first = bl.getFirstByteCodeInstructionNum( mn );
                    		System.out.println( bl+" with "+bl.getByteCodeInstructionCount( mn )+" instructions (first = "+first+")");                			
                		}
                	}
                }
				graphs.put( methodNodeToKey( mn ), graph );
            } else {
                logVerbose("Ignoring method "+mn.name);
            }
        }

        // second pass: rewrite methods and constructors by inserting custom code at the start of each basic block in the control flow graph
        logVerbose("Rewriting "+classToAnalyze+" ... ");

        final ClassWriter writer;
        if ( graphs.isEmpty() ) 
        {
            writer = new ClassWriter(0);
            provider.getClassReader().accept( writer , 0 );
        } else {
            writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);
            provider.getClassReader().accept( new MyClassVisitor( writer,graphs ) , 0 );
        }
        
        final byte[] result = writer.toByteArray();
        if ( debug ) {
        	final ClassReader reader = new ClassReader( result );
        	
        	final ClassNode classNode = new ClassNode();
        	reader.accept( classNode , 0 );
        	
        	System.out.println("==== Transformed class "+classToAnalyze+" ====");
        	
        	final ListIterator<MethodNode> it = classNode.methods.listIterator();
        	while( it.hasNext() ) {
        		final MethodNode mn = it.next();
        		if ( filter.matches( classToAnalyze , mn.name ) ) {
        			System.out.println("METHOD: "+mn.name+"_"+mn.desc);
        			System.out.println( Disassembler.disassemble( mn , true , true ) );
        		}
        	}
        }
        return result;
    }

    private static String methodNodeToKey(MethodNode mn) {
        return methodNodeToKey( mn.name , mn.desc );
    }

    private static String methodNodeToKey(String methodName,String methodDesc) {
        return methodName+"_"+methodDesc;
    }    

    protected final class MyClassVisitor extends ClassVisitor 
    {
        private final Map<String,ControlFlowGraph> graphs;

        protected MyClassVisitor(ClassVisitor cv,Map<String,ControlFlowGraph> graphs)
        {
            super(ASM4, cv);
            this.graphs = graphs;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions)
        {
            final MethodVisitor result = super.visitMethod(access, name, desc, signature, exceptions);

            final ControlFlowGraph cfg = graphs.get( methodNodeToKey( name , desc )  );

            if ( cfg == null ) // no CFG , write method unaltered
            {
                if ( debug ) {
                    System.out.println("DEBUG: Found no CFG for method "+methodNodeToKey( name , desc ) );
                }
                return result;
            }

            // determine number of slot where we'll store our newly introduced local variable (see below)
            // Since we already scanned the class file while creating the CFG , we can make use of this knowledge
            // (otherwise we would've to subclass LocalVariablesSorter)
            final int slotNr = cfg.getMethod().maxLocals;

            if ( debug ) {
                System.out.println("DEBUG: *** Rewriting method "+methodNodeToKey( name , desc )+" with "+slotNr+" local vars ***");
            }            

            /* Setup visitor stack:
             * 
             * 1. InstructionCountingVisitor - keeps track of the index of the current byte-code instruction within the method
             * 2. LoadVarVisitor - introduces a new local variable at the start of each method/constructor:  ExecutionStatistics $stat = StatisticsManager#getStatistics()
             * 3. BasicBlockVisitor - at the start of each control flow graph node , introduces byte-code that increments  $stat by the number of instructions in this block
             *                        and invokes StatisticsManager#account() if necessary 
             */
            final InstructionCountingVisitor visitor1 = new InstructionCountingVisitor();

            final boolean isConstructor = name.equals("<init>");
            final LoadVarVisitor visitor2 = new  LoadVarVisitor(new BasicBlockVisitor( result , visitor1 , cfg , slotNr , isConstructor ) , slotNr , isConstructor );

            visitor1.setDelegate( visitor2 );
            return visitor1;
        }
    }

    /**
     * This visitor generates bytecode at the start of a method that loads
     * the results of calling {@link StatisticsManager#getStatistics()} into a local variable.
     * 
     * <p>The generated byte-code is the equivalent of calling:
     * 
     * <pre>
     *   final ExecutionStatistics $stat = StatisticsManager#getStatistics();
     * </pre>
     * </p>
     * @author tobias.gierke@code-sourcery.de
     */
    protected final class LoadVarVisitor extends DeferredMethodVisitor {

        private boolean superConstructorInvoked = false;        
        private boolean atFirstLabel = true;

        private final boolean visitingConstructor;
        public final int variableSlot;

        // scope for our newly introduced variable , required for visitLocalVariable() call later
        private Label scopeStart;
        private Label scopeEnd;           

        protected LoadVarVisitor(MethodVisitor mv,int variableSlot,boolean visitingConstructor)
        {
            super(mv);
            this.variableSlot = variableSlot;
            this.visitingConstructor = visitingConstructor;
        }

        @Override
        public void visitCode()
        {
            super.visitCode();
            scopeStart = null;
            scopeEnd = null;            
        }        

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc)
        {
            super.visitMethodInsn(opcode, owner, name, desc);
            if ( visitingConstructor && ! superConstructorInvoked && opcode == Opcodes.INVOKESPECIAL && name.equals("<init>" ) ) {
                superConstructorInvoked = true;
                insertCode();
            }
        }

        private void insertCode() {
            if ( debug ) {
                System.out.println("DEBUG: >>>>>>>>>>>>>>>>>>> Loading ExecutionStatistics into local variable slot #"+variableSlot);
            }           
            scopeStart = new Label();
            super.visitLabel( scopeStart );
            super.visitMethodInsn(INVOKESTATIC, "de/codesourcery/asm/profiling/StatisticsManager", "getStatistics", "()Lde/codesourcery/asm/profiling/ExecutionStatistics;");
            super.visitVarInsn(ASTORE, variableSlot);                 
        }

        @Override
        public void visitLabel(Label label)
        {
            if ( scopeStart == null ) {
                scopeStart = label;
            }
            scopeEnd = label;

            super.visitLabel(label);

            // if visiting a regular method, we'll insert our code right after the first label
            // constructors get special treatment in visitMethodInsn()
            if ( ! visitingConstructor && atFirstLabel ) {
                atFirstLabel = false;
                insertCode();
            }
        }

        @Override
        public void visitLocalVariable(String name, String desc, String signature, Label start, Label end, int index)
        {
            super.visitLocalVariable(name, desc, signature, start, end, index);

            if ( index == variableSlot-1 ) 
            {
                if ( debug ) {
                    System.out.println("DEBUG: >>>>>>>>>>>>>>>>>>> Declaring local variable at slot #"+variableSlot);
                }                 
                // signature may be NULL if variable does not use generics
                String descriptor = Type.getDescriptor( ExecutionStatistics.class );
                super.visitLocalVariable("$stat", desc, descriptor , scopeStart, scopeEnd , variableSlot); 
            }
        }        
    }

    // for each basic control block, inserts code that increments ExecutionStatistics#executedInstructionCount for the current thread
    // and invokes StatisticsManager#account() once the instruction count is >= 0
    protected final class BasicBlockVisitor extends DeferredMethodVisitor 
    {
        private final boolean visitingConstructor;
        private final ControlFlowGraph cfg;
        private final InstructionCountingVisitor counter;
        private final int variableSlot;

        private boolean superConstructorCallSeen = false;

        protected BasicBlockVisitor(MethodVisitor mv,InstructionCountingVisitor instructionCounter,
                ControlFlowGraph cfg,int variableSlot,boolean visitingConstructor)
        {
            super(mv);
            this.counter = instructionCounter;
            this.cfg = cfg;
            this.variableSlot = variableSlot;
            this.visitingConstructor = visitingConstructor;
        }

        protected int currentInstructionNum() {
            return counter.getCurrentInstructionNum();
        }

        private void maybeInsertCode() 
        {
            final int insnNum = currentInstructionNum();
            final IBlock block = cfg.getBlockForInstruction( insnNum );

            if ( block == null ) {
                System.out.println("DEBUG: Found no block that starts at instruction "+insnNum);                
                return;
            }
            
            if ( block.isVirtual( cfg.getMethod() ) ) {
            	return;
            }

            if ( visitingConstructor && ! superConstructorCallSeen && block.getIndexOfSuperConstructorCall( cfg.getMethod() )+1 == insnNum ) 
            {
                superConstructorCallSeen = true;
                // we're inside a constructor and the current block contains the INVOKESPECIAL init()
                // => insert our code AFTER the INVOKESPECIAL instruction
                if ( debug ) {
                    System.out.println("DEBUG: >>>>>>>>>>>>>>>>>>> Inserting constructor code before instruction "+insnNum );
                    System.out.println("Block:\n"+block.disassemble( cfg.getMethod() , true , true ));
                }                
                insertCode( block );
            } 
            else if ( ( ! visitingConstructor || (visitingConstructor && superConstructorCallSeen) ) && 
                    block.getFirstByteCodeInstructionNum( cfg.getMethod() ) == insnNum )
            {
                // we're at the start of a basic block , inject custom code in front of it
                if ( debug ) {
                    System.out.println("DEBUG: >>>>>>>>>>>>>>>>>>> Inserting code before instruction "+insnNum );
                    System.out.println( block+" has "+block.getByteCodeInstructionCount( cfg.getMethod() )+" instructions." );
                    System.out.println("Block:\n"+block.disassemble( cfg.getMethod() , true , true ));
                }                
                insertCode( block );
            } 
        }

        /**
         * Insert bytecode.
         * 
         * <p>
         * This method inserts bytecode for the following java code:
         * 
         * <pre>
         *   // hint: the $stat variable has already been declared at the start of the method
         *   $stat.executedInstructionCount += &lt;Number of instructions in upcoming block&gt;;
         *   if ( $stat.executedInstructionCount >= 0 ) {
         *       StatisticsManager.account();
         *   }         
         * </pre>
         * 
         * </p>
         * @param block block that will begin on the next instruction
         */
        private void insertCode(IBlock block) 
        {
        	// note: local variable @ #variableSlot is already initialized with reference to the
        	// current thread's ExecutionStatistics instance here

            //            mv.visitMethodInsn(INVOKESTATIC, "de/codesourcery/asm/profiling/StatisticsManager", "getStatistics", "()Lde/codesourcery/asm/profiling/ExecutionStatistics;");
            //            mv.visitVarInsn(ASTORE, variableSlot);              

            // push reference to ExecutionStatistics on stack & duplicate it   
            super.visitVarInsn(ALOAD, variableSlot);
            super.visitInsn(DUP);

            /* Stack is now:
             * 
             * ExecutionStatistics <-- stack ptr
             * ExecutionStatistics 
             */
            // fetch the current value of ExecutionStatistics#executedInstructionCount and put it on the stack
            super.visitFieldInsn(GETFIELD, "de/codesourcery/asm/profiling/ExecutionStatistics", "executedInstructionCount", "I");

            // push the number of instructions in this block onto the stack
            final int insCount = block.getByteCodeInstructionCount(cfg.getMethod() );
            if ( insCount <= Byte.MAX_VALUE ) {
            	super.visitIntInsn(BIPUSH , insCount );
            } else if ( insCount <= Short.MAX_VALUE ) {
            	super.visitIntInsn(SIPUSH , insCount );
            } else {
            	super.visitLdcInsn( insCount );
            }

            /* Stack is now:
             * 
             * <instruction count >
             * ExecutionStatistics#executedInstructionCount
             * ExecutionStatistics 
             */

            // pops two values from the stack, adds them and pushes the result onto the stack
            super.visitInsn(IADD);

            /* Stack is now:
             * 
             * <instruction count > + ExecutionStatistics#executedInstructionCount
             * ExecutionStatistics 
             */              

            // update ExecutionStatistics#executedInstructionCount (pops value off the stack)
            super.visitFieldInsn(PUTFIELD, "de/codesourcery/asm/profiling/ExecutionStatistics", "executedInstructionCount", "I");

            // *** stack is now empty again ***

            // put ExecutionStatistics reference on stack 
            super.visitVarInsn(ALOAD, variableSlot);

            /* Stack is now:
             * 
             * ExecutionStatistics 
             */

            // read updated ExecutionStatistics#executedInstructionCount and put it on the stack
            super.visitFieldInsn(GETFIELD, "de/codesourcery/asm/profiling/ExecutionStatistics", "executedInstructionCount", "I");

            /* Stack is now:
             * 
             * ExecutionStatistics#executedInstructionCount 
             */

            final Label rest = new Label(); // label used to jump to the actual start of the current control block
            
            // conditional branch , do NOT invoke StatisticsManager#account() if value on stack is less than zero ( < 0 )
            super.visitJumpInsn(IFLT, rest);

            // *** stack is now empty again ***

            // invoke StatisticsManager#account()
            super.visitMethodInsn(INVOKESTATIC, "de/codesourcery/asm/profiling/StatisticsManager", "account", "()V");

            // assign location to label
            super.visitLabel(rest);
        }

        @Override
        public void visitInsn(int opcode)
        {
            maybeInsertCode();
            super.visitInsn(opcode);
        }

        @Override
        public void visitIntInsn(int opcode, int operand)
        {
            maybeInsertCode();
            super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitVarInsn(int opcode, int var)
        {
            maybeInsertCode();
            super.visitVarInsn(opcode, var);
        }

        @Override
        public void visitTypeInsn(int opcode, String type)
        {
            maybeInsertCode();
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String desc)
        {
            maybeInsertCode();
            super.visitFieldInsn(opcode, owner, name, desc);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc)
        {
            maybeInsertCode();
            super.visitMethodInsn(opcode, owner, name, desc);
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... bsmArgs)
        {
            maybeInsertCode();
            super.visitInvokeDynamicInsn(name, desc, bsm, bsmArgs);
        }

        @Override
        public void visitJumpInsn(int opcode, Label label)
        {
            maybeInsertCode();
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitLabel(Label label)
        {
            maybeInsertCode();
            super.visitLabel(label);
        }

        @Override
        public void visitLdcInsn(Object cst)
        {
            maybeInsertCode();
            super.visitLdcInsn(cst);
        }

        @Override
        public void visitIincInsn(int var, int increment)
        {
            maybeInsertCode();
            super.visitIincInsn(var, increment);
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels)
        {
            maybeInsertCode();
            super.visitTableSwitchInsn(min, max, dflt, labels);
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels)
        {
            maybeInsertCode();
            super.visitLookupSwitchInsn(dflt, keys, labels);
        }

        @Override
        public void visitMultiANewArrayInsn(String desc, int dims)
        {
            maybeInsertCode();
            super.visitMultiANewArrayInsn(desc, dims);
        }       
    }

    // subclass that exposes the protected "mv" field of MethodVisitor so
    // we can set the delegate after object construction
    protected class DeferredMethodVisitor extends MethodVisitor {

        protected DeferredMethodVisitor(MethodVisitor mv)
        {
            super(ASM4, mv);
        }

        protected DeferredMethodVisitor()
        {
            super(ASM4);
        }

        public void setDelegate(MethodVisitor mv) {
            this.mv = mv;
        }
    }

    // keeps track of the current byte-code instruction's index within a method
    protected final class InstructionCountingVisitor extends DeferredMethodVisitor 
    {
        protected int currentInstructionCount=0;

        public int getCurrentInstructionNum() {
            return currentInstructionCount;
        }

        private void incInsnCount() {
            currentInstructionCount++;
            if ( debug ) {
                System.out.println("DEBUG: Now at instruction "+currentInstructionCount);
            }
        }

        @Override
        public void visitInsn(int opcode)
        {
            super.visitInsn(opcode);
            incInsnCount();
        }

        @Override
        public void visitCode()
        {
            super.visitCode();
            currentInstructionCount = 0;
        }

        @Override
        public void visitIntInsn(int opcode, int operand)
        {
            super.visitIntInsn(opcode, operand);
            incInsnCount();
        }

        @Override
        public void visitVarInsn(int opcode, int var)
        {
            super.visitVarInsn(opcode, var);
            incInsnCount();
        }

        @Override
        public void visitTypeInsn(int opcode, String type)
        {
            super.visitTypeInsn(opcode, type);
            incInsnCount();
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String desc)
        {
            super.visitFieldInsn(opcode, owner, name, desc);
            incInsnCount();
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc)
        {
            super.visitMethodInsn(opcode, owner, name, desc);
            incInsnCount();
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... bsmArgs)
        {
            super.visitInvokeDynamicInsn(name, desc, bsm, bsmArgs);
            incInsnCount();
        }

        @Override
        public void visitJumpInsn(int opcode, Label label)
        {
            super.visitJumpInsn(opcode, label);
            incInsnCount();
        }

        @Override
        public void visitLabel(Label label)
        {
            super.visitLabel(label);
            incInsnCount();
        }

        @Override
        public void visitLdcInsn(Object cst)
        {
            super.visitLdcInsn(cst);
            incInsnCount();
        }

        @Override
        public void visitIincInsn(int var, int increment)
        {
            super.visitIincInsn(var, increment);
            incInsnCount();
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels)
        {
            super.visitTableSwitchInsn(min, max, dflt, labels);
            incInsnCount();
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels)
        {
            super.visitLookupSwitchInsn(dflt, keys, labels);
            incInsnCount();
        }

        @Override
        public void visitMultiANewArrayInsn(String desc, int dims)
        {
            super.visitMultiANewArrayInsn(desc, dims);
            incInsnCount();
        }

		@Override
		public void visitFrame(int type, int nLocal, Object[] local,
				int nStack, Object[] stack) {
			super.visitFrame(type, nLocal, local, nStack, stack);
            incInsnCount();			
		}

		@Override
		public void visitLineNumber(int line, Label start) {
			super.visitLineNumber(line, start);
            incInsnCount();			
		}
        
        
    }    
}