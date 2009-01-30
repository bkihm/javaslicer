package de.unisb.cs.st.javaslicer.tracer.instrumentation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import org.objectweb.asm.Attribute;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

import com.sun.jdi.LocalVariable;

import de.unisb.cs.st.javaslicer.common.classRepresentation.ReadClass;
import de.unisb.cs.st.javaslicer.common.classRepresentation.ReadMethod;
import de.unisb.cs.st.javaslicer.common.classRepresentation.instructions.AbstractInstruction;
import de.unisb.cs.st.javaslicer.tracer.Tracer;

public class TracingClassInstrumenter implements Opcodes {

    private final Tracer tracer;
    private final ReadClass readClass;

    public TracingClassInstrumenter(final ReadClass readClass, final Tracer tracer) {
        this(readClass, tracer, true);
    }

    protected TracingClassInstrumenter(final ReadClass readClass, final Tracer tracer,
            final boolean printDebug) {
        if (tracer.debug && printDebug)
            System.out.println("instrumenting " + readClass.getName());
        this.tracer = tracer;
        this.readClass = readClass;
    }

    @SuppressWarnings("unchecked")
    public void transform(final ClassNode classNode) {
        this.readClass.setSource(classNode.sourceFile);
        final ListIterator<MethodNode> methodIt = classNode.methods.listIterator();
        while (methodIt.hasNext()) {
            transformMethod(classNode, methodIt.next(), methodIt);
        }
        this.readClass.ready();
    }

    protected void transformMethod(final ClassNode classNode, final MethodNode method, final ListIterator<MethodNode> methodIt) {
        final ReadMethod readMethod = new ReadMethod(this.readClass, method.access,
                method.name, method.desc, AbstractInstruction.getNextIndex());
        this.readClass.addMethod(readMethod);

        // do not instrument <clinit> methods (break (linear) control flow)
        // because these methods may call other methods, we have to pause tracing when they are entered
        if ("<clinit>".equals(method.name)) {
            new PauseTracingInstrumenter(null, this.tracer).transformMethod(method, methodIt, this.readClass.getName());
            return;
        }

        MethodNode oldMethod;
        // only copy the old method if it has more than 2000 instructions
        if (method.instructions.size() > 2000) {
            oldMethod = new MethodNode();
            copyMethod(method, oldMethod);
        } else {
            oldMethod = null;
        }

        new TracingMethodInstrumenter(this.tracer, readMethod, classNode, method).transform(methodIt);

        // test the size of the instrumented method
        ClassWriter testCW = new ClassWriter(0);
        method.accept(testCW);
        int byteCodeSize = testCW.toByteArray().length;
        if (byteCodeSize >= 1 << 16) {
            System.err.format("WARNING: instrumented method \"%s.%s%s\" is larger than 64k bytes. undoing instrumentation.%n",
                this.readClass.getName(), readMethod.getName(), readMethod.getDesc());
            if (oldMethod == null) {
                System.err.println("ERROR: uninstrumented method had less than 2000 instructions, so we did not keep it...");
            } else {
                System.err.format("#instructions old: %d; #instructions new: %d; size new: %d%n",
                    oldMethod.instructions.size(), method.instructions.size(), byteCodeSize);
                copyMethod(oldMethod, method);
            }
        }

        // reset the labels
        Iterator<?> insnIt = method.instructions.iterator();
        while (insnIt.hasNext()) {
            Object insn = insnIt.next();
            if (insn instanceof LabelNode)
                ((LabelNode)insn).resetLabel();
        }

    }

    @SuppressWarnings("unchecked")
    private static void copyMethod(final MethodNode from, final MethodNode to) {
        to.access = from.access;
        to.annotationDefault = from.annotationDefault;
        to.attrs = from.attrs == null ? null : new ArrayList<Attribute>(from.attrs);
        to.desc = from.desc;
        to.exceptions = from.exceptions == null ? null : new ArrayList<String>(from.exceptions);
        to.instructions.clear();
        Iterator<?> insnIt = from.instructions.iterator();
        Map<LabelNode, LabelNode> labelsMap = new HashMap<LabelNode, LabelNode>() {
            private static final long serialVersionUID = 6883684625241587713L;

            @Override
            public LabelNode get(final Object key) {
                LabelNode label = super.get(key);
                if (label == null)
                    put((LabelNode) key, label = new LabelNode());
                return label;
            }
        };
        while (insnIt.hasNext()) {
            AbstractInsnNode insn = (AbstractInsnNode) insnIt.next();
            to.instructions.add(insn.clone(labelsMap ));
        }
        to.invisibleAnnotations = from.invisibleAnnotations == null ? null :
            new ArrayList<AnnotationNode>(from.invisibleAnnotations);
        if (from.invisibleParameterAnnotations == null) {
            to.invisibleParameterAnnotations = null;
        } else {
            to.invisibleParameterAnnotations = new List[from.invisibleParameterAnnotations.length];
            for (int i = 0; i < from.invisibleParameterAnnotations.length; ++i) {
                to.invisibleParameterAnnotations[i] = from.invisibleParameterAnnotations[i] == null
                    ? null : new ArrayList<AnnotationNode>(from.invisibleParameterAnnotations[i]);
            }
        }
        if (from.localVariables == null) {
            to.localVariables = null;
        } else {
            to.localVariables = new ArrayList<LocalVariable>(from.localVariables.size());
            for (Object lvObj: from.localVariables) {
                LocalVariableNode lv = (LocalVariableNode) lvObj;
                to.localVariables.add(new LocalVariableNode(
                    lv.name, lv.desc, lv.signature, labelsMap.get(lv.start),
                    labelsMap.get(lv.end), lv.index));
            }
        }
        to.maxLocals = from.maxLocals;
        to.maxStack = from.maxStack;
        to.name = from.name;
        to.signature = from.signature;
        if (from.tryCatchBlocks == null) {
            to.tryCatchBlocks = null;
        } else {
            to.tryCatchBlocks = new ArrayList<TryCatchBlockNode>(from.tryCatchBlocks.size());
            for (Object tcbObj: from.tryCatchBlocks) {
                TryCatchBlockNode tcb = (TryCatchBlockNode) tcbObj;
                to.tryCatchBlocks.add(new TryCatchBlockNode(
                    labelsMap.get(tcb.start), labelsMap.get(tcb.end),
                    labelsMap.get(tcb.handler), tcb.type));
            }
        }
        to.visibleAnnotations = from.visibleAnnotations == null ? null :
            new ArrayList<AnnotationNode>(from.visibleAnnotations);
        if (from.visibleParameterAnnotations == null) {
            to.visibleParameterAnnotations = null;
        } else {
            to.visibleParameterAnnotations = new List[from.visibleParameterAnnotations.length];
            for (int i = 0; i < from.visibleParameterAnnotations.length; ++i) {
                to.visibleParameterAnnotations[i] = from.visibleParameterAnnotations[i] == null
                    ? null : new ArrayList<AnnotationNode>(from.visibleParameterAnnotations[i]);
            }
        }
    }

}
