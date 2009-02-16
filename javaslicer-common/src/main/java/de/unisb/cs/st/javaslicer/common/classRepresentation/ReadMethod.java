package de.unisb.cs.st.javaslicer.common.classRepresentation;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;

import de.hammacher.util.ArrayQueue;
import de.hammacher.util.StringCacheInput;
import de.hammacher.util.StringCacheOutput;
import de.hammacher.util.maps.IntegerMap;
import de.hammacher.util.streams.OptimizedDataInputStream;
import de.hammacher.util.streams.OptimizedDataOutputStream;
import de.unisb.cs.st.javaslicer.common.classRepresentation.instructions.AbstractInstruction;
import de.unisb.cs.st.javaslicer.common.classRepresentation.instructions.LabelMarker;

public class ReadMethod implements Comparable<ReadMethod> {

    public static class MethodReadInformation {

        private final ReadMethod method;
        protected final IntegerMap<LabelMarker> labels = new IntegerMap<LabelMarker>();

        public MethodReadInformation(final ReadMethod method) {
            this.method = method;
        }

        public ReadMethod getMethod() {
            return this.method;
        }

        public LabelMarker getLabel(final int labelNr) throws IOException {
            final LabelMarker lm = this.labels.get(labelNr);
            if (lm == null)
                throw new IOException("corrupted data (illegal label)");
            return lm;
        }

    }

    private final ArrayList<AbstractInstruction> instructions = new ArrayList<AbstractInstruction>();
    private final List<TryCatchBlock> tryCatchBlocks = new ArrayList<TryCatchBlock>();
    private final ReadClass readClass;
    private final int access;
    private final String name;
    private final String desc;
    private final int instructionNumberStart;
    private int instructionNumberEnd;
    private LabelMarker methodEntryLabel;
    private LabelMarker methodLeaveLabel;
    private final ArrayList<LocalVariable> localVariables;

    public ReadMethod(final ReadClass readClass, final int access, final String name, final String desc, final int instructionNumberStart) {
        this.readClass = readClass;
        this.access = access;
        this.name = name;
        this.desc = desc;
        this.instructionNumberStart = instructionNumberStart;
        // default: no instructions
        this.instructionNumberEnd = instructionNumberStart;
        this.localVariables = new ArrayList<LocalVariable>();
    }

    public void addInstruction(final AbstractInstruction instruction) {
        this.instructions.add(instruction);
    }

    public void addTryCatchBlock(TryCatchBlock tryCatchBlock) {
        this.tryCatchBlocks.add(tryCatchBlock);
    }

    public void ready() {
        this.instructions.trimToSize();
    }

    public List<AbstractInstruction> getInstructions() {
        return this.instructions;
    }

    public List<TryCatchBlock> getTryCatchBlocks() {
        return this.tryCatchBlocks;
    }

    public ReadClass getReadClass() {
        return this.readClass;
    }

    public int getAccess() {
        return this.access;
    }

    public String getName() {
        return this.name;
    }

    public String getDesc() {
        return this.desc;
    }

    public int getInstructionNumberStart() {
        return this.instructionNumberStart;
    }

    public int getInstructionNumberEnd() {
        return this.instructionNumberEnd;
    }

    public void setInstructionNumberEnd(final int instructionNumberEnd) {
        this.instructionNumberEnd = instructionNumberEnd;
    }

    public LabelMarker getMethodEntryLabel() {
        return this.methodEntryLabel;
    }

    public void setMethodEntryLabel(final LabelMarker methodEntryLabel) {
        this.methodEntryLabel = methodEntryLabel;
    }

    public LabelMarker getMethodLeaveLabel() {
        return this.methodLeaveLabel;
    }

    public void setMethodLeaveLabel(final LabelMarker methodLeaveLabel) {
        this.methodLeaveLabel = methodLeaveLabel;
    }

    public List<LocalVariable> getLocalVariables() {
        return this.localVariables;
    }

    public void writeOut(final DataOutputStream out, final StringCacheOutput stringCache) throws IOException {
        OptimizedDataOutputStream.writeInt0(this.access, out);
        stringCache.writeString(this.name, out);
        stringCache.writeString(this.desc, out);
        OptimizedDataOutputStream.writeInt0(this.instructions.size(), out);
        for (final Instruction instr: this.instructions)
            if (instr instanceof LabelMarker)
                instr.writeOut(out, stringCache);
        for (final Instruction instr: this.instructions)
            if (!(instr instanceof LabelMarker))
                instr.writeOut(out, stringCache);
        OptimizedDataOutputStream.writeInt0(this.tryCatchBlocks.size(), out);
        for (TryCatchBlock tcb: this.tryCatchBlocks)
            tcb.writeOut(out, stringCache);
        if (this.methodEntryLabel != null && this.methodLeaveLabel != null) {
            assert this.methodEntryLabel == this.instructions.get(0);
            assert this.methodLeaveLabel == this.instructions.get(this.instructions.size()-1);
            out.writeBoolean(true);
        } else {
            out.writeBoolean(false);
        }
        OptimizedDataOutputStream.writeInt0(this.localVariables.size(), out);
        for (final LocalVariable v: this.localVariables)
            v.writeOut(out);
    }

    public static ReadMethod readFrom(final DataInputStream in, final ReadClass readClass, final int instructionNumberStart,
            final StringCacheInput stringCache) throws IOException {
        final int access = OptimizedDataInputStream.readInt0(in);
        final String name = stringCache.readString(in);
        final String desc = stringCache.readString(in);
        final ReadMethod rm = new ReadMethod(readClass, access, name, desc, instructionNumberStart);
        int numInstr = OptimizedDataInputStream.readInt0(in);
        rm.setInstructionNumberEnd(instructionNumberStart+numInstr);
        rm.instructions.ensureCapacity(numInstr);
        final Queue<LabelMarker> labels = new ArrayQueue<LabelMarker>();
        final MethodReadInformation mri = new MethodReadInformation(rm);
        AbstractInstruction instr = null;
        while (numInstr-- > 0) {
            instr = AbstractInstruction.readFrom(in, mri, stringCache);
            if (!(instr instanceof LabelMarker))
                break;
            final LabelMarker lm = (LabelMarker) instr;
            labels.add(lm);
            mri.labels.put(lm.getLabelNr(), lm);
            instr = null;
        }
        while (instr != null || numInstr-- > 0) {
            if (instr == null)
                instr = AbstractInstruction.readFrom(in, mri, stringCache);
            while (!labels.isEmpty() && labels.peek().getIndex() < instr.getIndex())
                rm.instructions.add(labels.poll());
            rm.instructions.add(instr);
            instr = null;
        }
        rm.instructions.addAll(labels);
        rm.instructions.trimToSize();
        int numTcb = OptimizedDataInputStream.readInt0(in);
        while (numTcb-- > 0) {
            rm.addTryCatchBlock(TryCatchBlock.readFrom(in, mri, stringCache));
        }

        final boolean hasEntryAndLeaveLabels = in.readBoolean();

        if (hasEntryAndLeaveLabels) {
            final AbstractInstruction methodEntryLabel = rm.instructions.get(0);
            if (methodEntryLabel instanceof LabelMarker)
                rm.setMethodEntryLabel((LabelMarker) methodEntryLabel);
            else
                throw new IOException("corrupted data");

            final AbstractInstruction methodLeaveLabel = rm.instructions.get(rm.instructions.size()-1);
            if (methodLeaveLabel instanceof LabelMarker)
                rm.setMethodLeaveLabel((LabelMarker) methodLeaveLabel);
            else
                throw new IOException("corrupted data");
        }

        int localVarsNr = OptimizedDataInputStream.readInt0(in);
        while (localVarsNr-- > 0)
            rm.localVariables.add(LocalVariable.readFrom(in));
        rm.localVariables.trimToSize();
        Collections.sort(rm.localVariables, new Comparator<LocalVariable>() {
            public int compare(final LocalVariable o1, final LocalVariable o2) {
                return o1.getIndex() - o2.getIndex();
            }
        });

        return rm;
    }

    /**
     * Returns the method name and description.
     */
    @Override
    public String toString() {
        return this.name + this.desc;
    }

    public int compareTo(final ReadMethod o) {
        int cmp = getReadClass().compareTo(o.getReadClass());
        if (cmp != 0)
            return cmp;
        cmp = this.name.compareTo(o.name);
        if (cmp != 0)
            return cmp;
        return this.desc.compareTo(o.desc);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + this.desc.hashCode();
        result = prime * result + this.name.hashCode();
        result = prime * result + this.readClass.hashCode();
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        ReadMethod other = (ReadMethod) obj;
        if (this.instructionNumberStart != other.instructionNumberStart)
            return false;
        if (!this.name.equals(other.name))
            return false;
        if (!this.desc.equals(other.desc))
            return false;
        if (!this.readClass.equals(other.readClass))
            return false;
        return true;
    }

}
