package de.unisb.cs.st.javaslicer.dependencyAnalysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

import de.hammacher.util.ArrayStack;
import de.hammacher.util.IntegerMap;
import de.unisb.cs.st.javaslicer.controlflowanalysis.ControlFlowAnalyser;
import de.unisb.cs.st.javaslicer.dependencyAnalysis.DependencyVisitor.DataDependencyType;
import de.unisb.cs.st.javaslicer.instructionSimulation.Simulator;
import de.unisb.cs.st.javaslicer.tracer.classRepresentation.Instruction;
import de.unisb.cs.st.javaslicer.tracer.classRepresentation.ReadMethod;
import de.unisb.cs.st.javaslicer.tracer.classRepresentation.Instruction.Instance;
import de.unisb.cs.st.javaslicer.tracer.classRepresentation.instructions.LabelMarker;
import de.unisb.cs.st.javaslicer.tracer.traceResult.TraceResult;
import de.unisb.cs.st.javaslicer.tracer.traceResult.ThreadTraceResult.ThreadId;
import de.unisb.cs.st.javaslicer.variableUsages.VariableUsages;
import de.unisb.cs.st.javaslicer.variables.Variable;

public class DependencyExtractor {

	public static enum VisitorCapabilities {
		DATA_DEPENDENCIES_ALL,
		DATA_DEPENDENCIES_READ_AFTER_WRITE,
		DATA_DEPENDENCIES_WRITE_AFTER_READ,
		CONTROL_DEPENDENCIES,
		INSTRUCTION_EXECUTIONS,
	}

    private final TraceResult trace;
	private final Simulator simulator = new Simulator();
	private ArrayList<DependencyVisitor> dataDependencyVisitorsReadAfterWrite = null;
	private ArrayList<DependencyVisitor> dataDependencyVisitorsWriteAfterRead = null;
	private ArrayList<DependencyVisitor> controlDependencyVisitors = null;
	private ArrayList<DependencyVisitor> instructionVisitors = null;

    public DependencyExtractor(final TraceResult trace) {
        this.trace = trace;
    }

    public boolean registerVisitor(final DependencyVisitor visitor, final EnumSet<VisitorCapabilities> capabilities) {
    	boolean change = false;
    	for (final VisitorCapabilities cap: capabilities) {
    		switch (cap) {
    		case DATA_DEPENDENCIES_ALL:
    			if (this.dataDependencyVisitorsReadAfterWrite == null)
    				this.dataDependencyVisitorsReadAfterWrite = new ArrayList<DependencyVisitor>();
    			change |= this.dataDependencyVisitorsReadAfterWrite.add(visitor);

    			if (this.dataDependencyVisitorsWriteAfterRead == null)
    				this.dataDependencyVisitorsWriteAfterRead = new ArrayList<DependencyVisitor>();
    			change |= this.dataDependencyVisitorsWriteAfterRead.add(visitor);
    			break;
    		case DATA_DEPENDENCIES_READ_AFTER_WRITE:
    			if (this.dataDependencyVisitorsReadAfterWrite == null)
    				this.dataDependencyVisitorsReadAfterWrite = new ArrayList<DependencyVisitor>();
    			change |= this.dataDependencyVisitorsReadAfterWrite.add(visitor);
    			break;
    		case DATA_DEPENDENCIES_WRITE_AFTER_READ:
    			if (this.dataDependencyVisitorsWriteAfterRead == null)
    				this.dataDependencyVisitorsWriteAfterRead = new ArrayList<DependencyVisitor>();
    			change |= this.dataDependencyVisitorsWriteAfterRead.add(visitor);
    			break;
    		case CONTROL_DEPENDENCIES:
    			if (this.controlDependencyVisitors == null)
    				this.controlDependencyVisitors = new ArrayList<DependencyVisitor>();
    			change |= this.controlDependencyVisitors.add(visitor);
    			break;
    		case INSTRUCTION_EXECUTIONS:
    			if (this.instructionVisitors == null)
    				this.instructionVisitors = new ArrayList<DependencyVisitor>();
    			change |= this.instructionVisitors.add(visitor);
    			break;
    		}
    	}
    	return change;
    }

    public boolean unregisterVisitor(final DependencyVisitor visitor) {
    	boolean change = false;
    	if (this.dataDependencyVisitorsReadAfterWrite.remove(visitor)) {
    		change = true;
    		if (this.dataDependencyVisitorsReadAfterWrite.isEmpty())
    			this.dataDependencyVisitorsReadAfterWrite = null;
    	}
    	if (this.dataDependencyVisitorsWriteAfterRead.remove(visitor)) {
    		change = true;
    		if (this.dataDependencyVisitorsWriteAfterRead.isEmpty())
    			this.dataDependencyVisitorsWriteAfterRead = null;
    	}
    	if (this.controlDependencyVisitors.remove(visitor)) {
    		change = true;
    		if (this.controlDependencyVisitors.isEmpty())
    			this.controlDependencyVisitors = null;
    	}
    	if (this.instructionVisitors.remove(visitor)) {
    		change = true;
    		if (this.instructionVisitors.isEmpty())
    			this.instructionVisitors = null;
    	}
    	return change;
    }

    public void processBackwardTrace(final long javaThreadId) {
    	final ThreadId id = this.trace.getThreadId(javaThreadId);
    	if (id != null)
    		processBackwardTrace(id);
    }

    public void processBackwardTrace(final ThreadId threadId) {
        final Iterator<Instance> backwardInsnItr = this.trace.getBackwardIterator(threadId);

        final IntegerMap<Set<Instruction>> controlDependencies = new IntegerMap<Set<Instruction>>();

        final ArrayStack<ExecutionFrame> frames = new ArrayStack<ExecutionFrame>();

        ExecutionFrame currentFrame = new ExecutionFrame();
        frames.push(currentFrame);

        final Map<Variable, Instance> lastWriter = new HashMap<Variable, Instance>();
        final Map<Variable, List<Instance>> lastReaders = new HashMap<Variable, List<Instance>>();

        while (backwardInsnItr.hasNext()) {
            final Instance instance = backwardInsnItr.next();
            final Instruction instruction = instance.getInstruction();

            ExecutionFrame removedFrame = null;
            final int stackDepth = instance.getStackDepth();
            assert stackDepth >= 0;

            if (frames.size() != stackDepth) {
                if (frames.size() > stackDepth) {
                    assert frames.size() == stackDepth+1;
                    removedFrame = frames.pop();
                } else {
                    ExecutionFrame topFrame = null;
                    while (frames.size() < stackDepth) {
                        if (topFrame == null && frames.size() > 0)
                            topFrame = frames.peek();
                        final ExecutionFrame newFrame = new ExecutionFrame();
                        if (topFrame != null && topFrame.atCacheBlockStart != null)
                            newFrame.throwsException = true;
                        frames.push(newFrame);
                    }
                }
                currentFrame = frames.peek();
            }

            // it is possible that we see successive instructions of different methods,
            // e.g. when called from native code
            if (currentFrame.method != instruction.getMethod()) {
                if (currentFrame.method == null) {
                    currentFrame.method = instruction.getMethod();
                } else {
                    currentFrame = new ExecutionFrame();
                    currentFrame.method = instruction.getMethod();
                    frames.set(stackDepth-1, currentFrame);
                }
            }

            final VariableUsages dynInfo = this.simulator.simulateInstruction(instance, currentFrame,
                    removedFrame, frames);

            if (this.instructionVisitors != null)
            	for (final DependencyVisitor vis: this.instructionVisitors)
            		vis.visitInstructionExecution(instance);

            if (this.controlDependencyVisitors != null) {
	            Set<Instruction> instrControlDependencies = controlDependencies.get(instruction.getIndex());
	            if (instrControlDependencies == null) {
	                computeControlDependencies(instruction.getMethod(), controlDependencies);
	                instrControlDependencies = controlDependencies.get(instruction.getIndex());
	                assert instrControlDependencies != null;
	            }
	            // get all interesting instructions, that are dependent on the current one
	            Set<Instruction> dependantInterestingInstructions = intersect(instrControlDependencies,
	                    currentFrame.interestingInstructions);
	            if (currentFrame.throwsException) {
	                currentFrame.throwsException = false;
	                // in this case, we have an additional control dependency from the catching to
	                // the throwing instruction
	                for (int i = stackDepth-2; i >= 0; --i) {
	                    final ExecutionFrame f = frames.get(i);
	                    if (f.atCacheBlockStart != null) {
	                        if (f.interestingInstructions.contains(f.atCacheBlockStart)) {
	                            if (dependantInterestingInstructions.isEmpty())
	                                dependantInterestingInstructions = Collections.singleton((Instruction)f.atCacheBlockStart);
	                            else
	                                dependantInterestingInstructions.add(f.atCacheBlockStart);
	                        }
	                        break;
	                    }
	                }
	            }
	            if (!dependantInterestingInstructions.isEmpty()) {
	            	for (final Instruction depend: dependantInterestingInstructions) {
	            		for (final DependencyVisitor vis: this.controlDependencyVisitors) {
	            			vis.visitControlDependency(depend, instruction);
	            		}
	            	}
	                currentFrame.interestingInstructions.removeAll(dependantInterestingInstructions);
	            }
                currentFrame.interestingInstructions.add(instruction);
            }

            if (!dynInfo.getDefinedVariables().isEmpty()) {
	            if (this.dataDependencyVisitorsReadAfterWrite != null) {
	                for (final Variable definedVariable: dynInfo.getDefinedVariables()) {
	                	final List<Instance> readers = lastReaders.get(definedVariable);
	                	if (readers != null)
		                	for (final Instance reader: readers) {
		                		for (final DependencyVisitor vis: this.dataDependencyVisitorsReadAfterWrite)
		                			vis.visitDataDependency(reader, instance, definedVariable, DataDependencyType.READ_AFTER_WRITE);
		                	}
	                	lastReaders.remove(definedVariable);
	                	if (this.dataDependencyVisitorsWriteAfterRead != null)
	                		lastWriter.put(definedVariable, instance);
	                }
	            } else if (this.dataDependencyVisitorsWriteAfterRead != null) {
	                for (final Variable definedVariable: dynInfo.getDefinedVariables()) {
	                	lastReaders.remove(definedVariable);
	                	lastWriter.put(definedVariable, instance);
	                }
	            }
            }

            if (!dynInfo.getUsedVariables().isEmpty()) {
	            if (this.dataDependencyVisitorsWriteAfterRead != null) {
	            	for (final Variable usedVariable: dynInfo.getUsedVariables()) {
	            		final Instance lastWriterInst = lastWriter.get(usedVariable);

	            		if (lastWriterInst != null) {
	            			for (final DependencyVisitor vis: this.dataDependencyVisitorsWriteAfterRead)
	            				vis.visitDataDependency(lastWriterInst, instance, usedVariable, DataDependencyType.WRITE_AFTER_READ);
	            		}

	            		if (this.dataDependencyVisitorsReadAfterWrite != null) {
		            		List<Instance> readers = lastReaders.get(usedVariable);
		            		if (readers == null) {
		            			readers = new ArrayList<Instance>(4);
		            			lastReaders.put(usedVariable, readers);
		            		}
		            		readers.add(instance);
	            		}
	                }
	            } else if (this.dataDependencyVisitorsReadAfterWrite != null) {
	            	for (final Variable usedVariable: dynInfo.getUsedVariables()) {
	            		List<Instance> readers = lastReaders.get(usedVariable);
	            		if (readers == null) {
	            			readers = new ArrayList<Instance>(4);
	            			lastReaders.put(usedVariable, readers);
	            		}
	            		readers.add(instance);
	                }
	            }
            }

            if (dynInfo.isCatchBlock())
                currentFrame.atCacheBlockStart = (LabelMarker) instruction;
            else if (currentFrame.atCacheBlockStart != null)
                currentFrame.atCacheBlockStart = null;

        }
    }

    private void computeControlDependencies(final ReadMethod method, final IntegerMap<Set<Instruction>> controlDependencies) {
        final Map<Instruction, Set<Instruction>> deps = ControlFlowAnalyser.getInstance().getInvControlDependencies(method);
        for (final Entry<Instruction, Set<Instruction>> entry: deps.entrySet()) {
            final int index = entry.getKey().getIndex();
            assert !controlDependencies.containsKey(index);
            controlDependencies.put(index, entry.getValue());
        }
    }

    private static <T> Set<T> intersect(final Set<T> set1,
            final Set<T> set2) {
        if (set1.size() == 0 || set2.size() == 0)
            return Collections.emptySet();

        Set<T> smallerSet;
        Set<T> biggerSet;
        if (set1.size() < set2.size()) {
            smallerSet = set1;
            biggerSet = set2;
        } else {
            smallerSet = set2;
            biggerSet = set1;
        }

        Set<T> intersection = null;
        for (final T obj: smallerSet) {
            if (biggerSet.contains(obj)) {
                if (intersection == null)
                    intersection = new HashSet<T>();
                intersection.add(obj);
            }
        }

        if (intersection == null)
            return Collections.emptySet();
        return intersection;
    }

}
