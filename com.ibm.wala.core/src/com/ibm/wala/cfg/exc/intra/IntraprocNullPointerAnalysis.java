package com.ibm.wala.cfg.exc.intra;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.dataflow.graph.DataflowSolver;
import com.ibm.wala.ipa.cfg.PrunedCFG;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSAArrayLengthInstruction;
import com.ibm.wala.ssa.SSAArrayLoadInstruction;
import com.ibm.wala.ssa.SSAArrayStoreInstruction;
import com.ibm.wala.ssa.SSABinaryOpInstruction;
import com.ibm.wala.ssa.SSACheckCastInstruction;
import com.ibm.wala.ssa.SSAComparisonInstruction;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAConversionInstruction;
import com.ibm.wala.ssa.SSAGetCaughtExceptionInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAGotoInstruction;
import com.ibm.wala.ssa.SSAInstanceofInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInstruction.IVisitor;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSALoadMetadataInstruction;
import com.ibm.wala.ssa.SSAMonitorInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SSAPiInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.ssa.SSAReturnInstruction;
import com.ibm.wala.ssa.SSASwitchInstruction;
import com.ibm.wala.ssa.SSAThrowInstruction;
import com.ibm.wala.ssa.SSAUnaryOpInstruction;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.MonitorUtil.IProgressMonitor;
import com.ibm.wala.util.graph.Graph;
import com.ibm.wala.util.graph.impl.SparseNumberedGraph;

/**
 * Intraprocedural dataflow analysis to detect impossible NullPointerExceptions.
 * 
 * @author Juergen Graf <graf@kit.edu>
 *
 */
public class IntraprocNullPointerAnalysis<T extends ISSABasicBlock> {

  private NullPointerSolver<T> solver;

	private final Set<TypeReference> ignoreExceptions;
	private final IR ir;
	private final ControlFlowGraph<SSAInstruction, T> cfg;
	private final int maxVarNum;
	private int deletedEdges;
	private ControlFlowGraph<SSAInstruction, T> pruned = null;

	private final ParameterState initialState;
	private final MethodState mState;

	IntraprocNullPointerAnalysis(IR ir, ControlFlowGraph<SSAInstruction, T> cfg,
			TypeReference[] ignoreExceptions, ParameterState initialState, MethodState mState) {
		this.cfg = cfg;
		this.ir = ir;
		if (ir == null || ir.isEmptyIR()) {
			maxVarNum = -1;
		} else {
			maxVarNum = ir.getSymbolTable().getMaxValueNumber();
		}
		
		this.ignoreExceptions = new HashSet<TypeReference>();
		
		if (ignoreExceptions != null) {
  		for (TypeReference tRef : ignoreExceptions) {
  				this.ignoreExceptions.add(tRef);
  		}
		}
		
		this.initialState = initialState;
		this.mState = mState;
	}

	void run(IProgressMonitor progress) throws CancelException {
		if (pruned == null) {
			if (ir == null || ir.isEmptyIR()) {
				pruned = cfg;
			} else {
				NullPointerFrameWork<T> problem = new NullPointerFrameWork<T>(cfg, ir);
				int[] paramValNum = ir.getParameterValueNumbers();
			
				solver = new NullPointerSolver<T>(problem, maxVarNum, paramValNum, initialState);
				
				if (solver.solve(progress)) {
					// we were able to remove some exceptions
					Graph<T> deleted = createDeletedGraph(solver);
					for (T node : deleted) {
						deletedEdges += deleted.getSuccNodeCount(node);
					}
					NegativeGraphFilter<T> filter = new NegativeGraphFilter<T>(deleted);
					
					pruned = PrunedCFG.make(cfg, filter);
				} else {
					pruned = cfg;
				}
			}
		}
	}
	
	private Graph<T> createDeletedGraph(NullPointerSolver<T> solver) {
		NegativeCFGBuilderVisitor nCFGbuilder = new NegativeCFGBuilderVisitor(solver);
		for (T bb : cfg) {
			nCFGbuilder.work(bb);
		}
		
		Graph<T> deleted = nCFGbuilder.getNegativeCFG();
		
		return deleted;
	}
	
	ControlFlowGraph<SSAInstruction, T> getPrunedCfg() {
		if (pruned == null) {
			throw new IllegalStateException("Run analysis first! (call run())");
		}
		
		return pruned;
	}
	
	int getNumberOfDeletedEdges() {
		if (pruned == null) {
			throw new IllegalStateException("Run analysis first! (call run())");
		}
		
		return deletedEdges;
	}
	
	public NullPointerState getState(T block) {
		assert pruned != null || solver != null : "No solver initialized for method " + ir.getMethod().toString();
		if (pruned != null && solver == null) {
			// empty IR ... so states have not changed and we can return the initial state as a save approximation 
			return new NullPointerState(maxVarNum, ir.getSymbolTable(), initialState);
		} else {
			return solver.getIn(block);
		}
	}
	
	private class NullPointerSolver<B extends ISSABasicBlock> extends DataflowSolver<B, NullPointerState> {

		private final int maxVarNum;
		private final ParameterState parameterState;

		private NullPointerSolver(NullPointerFrameWork<B> problem, int maxVarNum, int[] paramVarNum) {
			this(problem, maxVarNum, paramVarNum, null);
		}
		
		private NullPointerSolver(NullPointerFrameWork<B> problem, int maxVarNum, int[] paramVarNum, ParameterState initialState) {
			super(problem);
			this.maxVarNum = maxVarNum;
			this.parameterState = initialState;
		}
		
		/* (non-Javadoc)
		 * @see com.ibm.wala.dataflow.graph.DataflowSolver#makeEdgeVariable(java.lang.Object, java.lang.Object)
		 */
		@Override
		protected NullPointerState makeEdgeVariable(B src, B dst) {
			return new NullPointerState(maxVarNum, ir.getSymbolTable(), parameterState);
		}

		/* (non-Javadoc)
		 * @see com.ibm.wala.dataflow.graph.DataflowSolver#makeNodeVariable(java.lang.Object, boolean)
		 */
		@Override
		protected NullPointerState makeNodeVariable(B n, boolean IN) {
			return new NullPointerState(maxVarNum, ir.getSymbolTable(), parameterState);
		}
		
	}
	
	private class NegativeCFGBuilderVisitor implements IVisitor {

		private final Graph<T> deleted;
		private final NullPointerSolver<T> solver;
		
		private NegativeCFGBuilderVisitor(NullPointerSolver<T> solver) {
			this.solver = solver;
			this.deleted = new SparseNumberedGraph<T>(2);
			for (T bb : cfg) {
				deleted.addNode(bb);
			}
		}
		
		private NullPointerState currentState;
		private T  currentBlock;
		
		public void work(T bb) {
			if (bb == null) {
				throw new IllegalArgumentException("Null not allowed");
			} else if (!cfg.containsNode(bb)) {
				throw new IllegalArgumentException("Block not part of current CFG");
			}
			
			SSAInstruction instr = NullPointerTransferFunctionProvider.getRelevantInstruction(bb);
			
			if (instr != null) {
				currentState = solver.getIn(bb);
				currentBlock = bb;
				instr.visit(this);
				currentState = null;
				currentBlock = null;
			}
		}
		
		public Graph<T> getNegativeCFG() {
			return deleted;
		}
		
		/**
		 * We have to be careful here. If the invoke instruction can not throw a NullPointerException,
		 * because the reference object is not null, the method itself may throw a NullPointerException. 
		 * So we can only remove the edge if the method itself throws no exception.
		 */
		private boolean isOnlyNullPointerExc(SSAInstruction instr) {
			assert instr.isPEI();
			
			if (instr instanceof SSAAbstractInvokeInstruction) {
				return mState != null && !mState.throwsException((SSAAbstractInvokeInstruction) instr);	
			} else {
				Collection<TypeReference> exc = instr.getExceptionTypes();
				Set<TypeReference> myExcs = new HashSet<TypeReference>(exc);
				myExcs.removeAll(ignoreExceptions);
				
				return myExcs.size() == 1 && myExcs.contains(TypeReference.JavaLangNullPointerException);
			}
		}
		
		private boolean noExceptions(SSAInstruction instr) {
      assert instr.isPEI();
      
      if (instr instanceof SSAAbstractInvokeInstruction) {
        return mState != null && !mState.throwsException((SSAAbstractInvokeInstruction) instr); 
      } else {
        Collection<TypeReference> exc = instr.getExceptionTypes();
        Set<TypeReference> myExcs = new HashSet<TypeReference>(exc);
        myExcs.removeAll(ignoreExceptions);
        
        return myExcs.isEmpty();
      }
		}
		
		private void removeImpossibleSuccessors(SSAInstruction instr, int varNum) {
			if (isOnlyNullPointerExc(instr)) {
				if (currentState.isNeverNull(varNum)) {
					for (T succ : cfg.getExceptionalSuccessors(currentBlock)) {
						deleted.addEdge(currentBlock, succ);
					}
					
				} else if (currentState.isAlwaysNull(varNum)) {
					for (T succ : cfg.getNormalSuccessors(currentBlock)) {
						deleted.addEdge(currentBlock, succ);
					}
				}
			}
		}
		
    private void removeImpossibleSuccessors(SSAInstruction instr) {
      if (noExceptions(instr)) {
        for (T succ : cfg.getExceptionalSuccessors(currentBlock)) {
          deleted.addEdge(currentBlock, succ);
        }
      }
    }
    
		/* (non-Javadoc)
		 * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitArrayLength(com.ibm.wala.ssa.SSAArrayLengthInstruction)
		 */
		public void visitArrayLength(SSAArrayLengthInstruction instruction) {
			int varNum = instruction.getArrayRef();
			removeImpossibleSuccessors(instruction, varNum);
		}

		/* (non-Javadoc)
		 * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitArrayLoad(com.ibm.wala.ssa.SSAArrayLoadInstruction)
		 */
		public void visitArrayLoad(SSAArrayLoadInstruction instruction) {
			int varNum = instruction.getArrayRef();
			removeImpossibleSuccessors(instruction, varNum);
		}

		/* (non-Javadoc)
		 * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitArrayStore(com.ibm.wala.ssa.SSAArrayStoreInstruction)
		 */
		public void visitArrayStore(SSAArrayStoreInstruction instruction) {
			int varNum = instruction.getArrayRef();
			removeImpossibleSuccessors(instruction, varNum);
		}

		/* (non-Javadoc)
		 * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitBinaryOp(com.ibm.wala.ssa.SSABinaryOpInstruction)
		 */
		public void visitBinaryOp(SSABinaryOpInstruction instruction) {}

		/* (non-Javadoc)
		 * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitCheckCast(com.ibm.wala.ssa.SSACheckCastInstruction)
		 */
		public void visitCheckCast(SSACheckCastInstruction instruction) {}

		/* (non-Javadoc)
		 * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitComparison(com.ibm.wala.ssa.SSAComparisonInstruction)
		 */
		public void visitComparison(SSAComparisonInstruction instruction) {}

		/* (non-Javadoc)
		 * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitConditionalBranch(com.ibm.wala.ssa.SSAConditionalBranchInstruction)
		 */
		public void visitConditionalBranch(SSAConditionalBranchInstruction instruction) {}

		/* (non-Javadoc)
		 * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitConversion(com.ibm.wala.ssa.SSAConversionInstruction)
		 */
		public void visitConversion(SSAConversionInstruction instruction) {}

		/* (non-Javadoc)
		 * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitGet(com.ibm.wala.ssa.SSAGetInstruction)
		 */
		public void visitGet(SSAGetInstruction instruction) {
			if (!instruction.isStatic()) {
				int varNum = instruction.getRef();
				removeImpossibleSuccessors(instruction, varNum);
			}
		}

		/* (non-Javadoc)
		 * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitGetCaughtException(com.ibm.wala.ssa.SSAGetCaughtExceptionInstruction)
		 */
		public void visitGetCaughtException(SSAGetCaughtExceptionInstruction instruction) {}

		/* (non-Javadoc)
		 * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitGoto(com.ibm.wala.ssa.SSAGotoInstruction)
		 */
		public void visitGoto(SSAGotoInstruction instruction) {}

		/* (non-Javadoc)
		 * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitInstanceof(com.ibm.wala.ssa.SSAInstanceofInstruction)
		 */
		public void visitInstanceof(SSAInstanceofInstruction instruction) {}

		/* (non-Javadoc)
		 * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitInvoke(com.ibm.wala.ssa.SSAInvokeInstruction)
		 */
		public void visitInvoke(SSAInvokeInstruction instruction) {
			if (!instruction.isStatic()) {
				int varNum = instruction.getReceiver();
				removeImpossibleSuccessors(instruction, varNum);
			} else {
			  removeImpossibleSuccessors(instruction);
			}
		}

		/* (non-Javadoc)
		 * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitLoadMetadata(com.ibm.wala.ssa.SSALoadMetadataInstruction)
		 */
		public void visitLoadMetadata(SSALoadMetadataInstruction instruction) {}

		/* (non-Javadoc)
		 * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitMonitor(com.ibm.wala.ssa.SSAMonitorInstruction)
		 */
		public void visitMonitor(SSAMonitorInstruction instruction) {
			int varNum = instruction.getRef();
			removeImpossibleSuccessors(instruction, varNum);
		}

		/* (non-Javadoc)
		 * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitNew(com.ibm.wala.ssa.SSANewInstruction)
		 */
		public void visitNew(SSANewInstruction instruction) {
			removeImpossibleSuccessors(instruction);
		}

		/* (non-Javadoc)
		 * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitPhi(com.ibm.wala.ssa.SSAPhiInstruction)
		 */
		public void visitPhi(SSAPhiInstruction instruction) {}

		/* (non-Javadoc)
		 * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitPi(com.ibm.wala.ssa.SSAPiInstruction)
		 */
		public void visitPi(SSAPiInstruction instruction) {}

		/* (non-Javadoc)
		 * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitPut(com.ibm.wala.ssa.SSAPutInstruction)
		 */
		public void visitPut(SSAPutInstruction instruction) {
			if (!instruction.isStatic()) {
				int varNum = instruction.getRef();
				removeImpossibleSuccessors(instruction, varNum);
			}
		}

		/* (non-Javadoc)
		 * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitReturn(com.ibm.wala.ssa.SSAReturnInstruction)
		 */
		public void visitReturn(SSAReturnInstruction instruction) {}

		/* (non-Javadoc)
		 * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitSwitch(com.ibm.wala.ssa.SSASwitchInstruction)
		 */
		public void visitSwitch(SSASwitchInstruction instruction) {}

		/* (non-Javadoc)
		 * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitThrow(com.ibm.wala.ssa.SSAThrowInstruction)
		 */
		public void visitThrow(SSAThrowInstruction instruction) {}

		/* (non-Javadoc)
		 * @see com.ibm.wala.ssa.SSAInstruction.IVisitor#visitUnaryOp(com.ibm.wala.ssa.SSAUnaryOpInstruction)
		 */
		public void visitUnaryOp(SSAUnaryOpInstruction instruction) {}

	}
	
}
