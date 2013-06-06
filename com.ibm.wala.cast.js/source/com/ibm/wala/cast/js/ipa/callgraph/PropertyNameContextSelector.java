/*******************************************************************************
 * Copyright (c) 2008 IBM Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.wala.cast.js.ipa.callgraph;

import java.util.Collections;
import java.util.HashMap;

import com.ibm.wala.cast.ir.ssa.AbstractReflectiveGet;
import com.ibm.wala.cast.ir.ssa.AstIRFactory;
import com.ibm.wala.cast.ir.ssa.AstIsDefinedInstruction;
import com.ibm.wala.cast.js.ipa.callgraph.correlations.CorrelationFinder;
import com.ibm.wala.cast.js.ipa.callgraph.correlations.extraction.ClosureExtractor;
import com.ibm.wala.cast.js.types.JavaScriptTypes;
import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.ContextItem;
import com.ibm.wala.ipa.callgraph.ContextKey;
import com.ibm.wala.ipa.callgraph.ContextSelector;
import com.ibm.wala.ipa.callgraph.impl.Everywhere;
import com.ibm.wala.ipa.callgraph.propagation.ConcreteTypeKey;
import com.ibm.wala.ipa.callgraph.propagation.FilteredPointerKey.SingleInstanceFilter;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ReflectiveMemberAccess;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAOptions;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.util.collections.HashMapFactory;
import com.ibm.wala.util.collections.Iterator2Iterable;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.IntSetUtil;
import com.ibm.wala.util.intset.MutableIntSet;

/**
 * A context selector that applies object sensitivity for the i'th parameter
 * if it is used as a property name in a dynamic property access.
 * 
 * Works together with {@link CorrelationFinder} and {@link ClosureExtractor}
 * to implement correlation tracking.
 */
public class PropertyNameContextSelector implements ContextSelector {
  public final static ContextKey PROPNAME_KEY = new ContextKey() { };
  public static final ContextItem PROPNAME_MARKER = new ContextItem() { };

  public final static ContextKey PROPNAME_PARM_INDEX = new ContextKey() { };
  
  /** Context representing a particular name accessed by a correlated read/write pair. */
  public class PropNameContext extends SelectiveCPAContext {
    PropNameContext(Context base, InstanceKey obj) {
      super(base, Collections.singletonMap(ContextKey.PARAMETERS[index], obj));
    }
    
    @Override
    public ContextItem get(ContextKey key) {
      if (PROPNAME_KEY.equals(key)) {
        return PROPNAME_MARKER;
      } else if(PROPNAME_PARM_INDEX.equals(key)) {
        return ContextItem.Value.make(index);
      } else {
        return super.get(key);
      }
    }
    
    @Override
    public String toString() {
      return "property name context for " + get(ContextKey.PARAMETERS[index]) + " over " + this.base;
    }

    /**
     * get the {@link InstanceKey} used to distinguish this context 
     */
    public InstanceKey getInstanceKey() {
      return ((SingleInstanceFilter)get(ContextKey.PARAMETERS[index])).getInstance();
    }
  }
   
  /**
   * A "dummy" for-in context used for callees of a method analyzed in a real
   * {@link PropNameContext}. The purpose of this class is to clone callees based
   * on the same {@link InstanceKey} used for the caller context, but without
   * returning a {@link SingleInstanceFilter} {@link ContextItem} that filters
   * possible parameter values.
   */
  class MarkerForInContext extends PropNameContext {

    MarkerForInContext(Context base, InstanceKey obj) {
      super(base, obj);
    }

    /**
     * Like {@link PropNameContext#get(ContextKey)}, but don't return a
     * {@link SingleInstanceFilter} for the distinguishing {@link InstanceKey}
     */
    @Override
    public ContextItem get(ContextKey key) {
      final ContextItem contextItem = super.get(key);
      return (contextItem instanceof SingleInstanceFilter) ? null : contextItem;
    }

    /**
     * we need to override this method since
     * {@link MarkerForInContext#get(ContextKey)} does not return the
     * {@link SingleInstanceFilter} containing the {@link InstanceKey}. Instead,
     * we invoke {@link PropNameContext#get(ContextKey)} from the superclass.
     */
    @Override
    public InstanceKey getInstanceKey() {
      return ((SingleInstanceFilter)super.get(ContextKey.PARAMETERS[index])).getInstance();
    }
    
  }
  
  private final AnalysisCache cache;
  private final ContextSelector base;
  private final int index;
  
  private void collectValues(DefUse du, SSAInstruction inst, MutableIntSet values) {
    if (inst instanceof SSAGetInstruction) {
      SSAGetInstruction g = (SSAGetInstruction) inst;
      values.add(g.getRef());
      if (g.getRef() != -1) {
        collectValues(du, du.getDef(g.getRef()), values);
      }
    } else if (inst instanceof AbstractReflectiveGet) {
      AbstractReflectiveGet g = (AbstractReflectiveGet) inst;
      values.add(g.getObjectRef());
      collectValues(du, du.getDef(g.getObjectRef()), values);
      values.add(g.getMemberRef());
      collectValues(du, du.getDef(g.getMemberRef()), values);
    }
  }
  
  private IntSet identifyDependentParameters(CGNode caller, CallSiteReference site) {
    MutableIntSet dependentParameters = IntSetUtil.make();
    SSAAbstractInvokeInstruction inst = caller.getIR().getCalls(site)[0];
    DefUse du = caller.getDU();
    
    for(int i = 0; i < inst.getNumberOfParameters(); i++) {
      MutableIntSet values = IntSetUtil.make();
      values.add(inst.getUse(i));
      collectValues(du, du.getDef(inst.getUse(i)), values);
      if (values.contains(index+1))
        dependentParameters.add(i);
    }
    
    return dependentParameters;
  }
  
  public PropertyNameContextSelector(AnalysisCache cache, ContextSelector base) {
    this(cache, 2, base);
  }
  
  public PropertyNameContextSelector(AnalysisCache cache, int index, ContextSelector base) {
    this.cache = cache;
    this.index = index;
    this.base = base;
  }
  
  private enum Frequency { NEVER, SOMETIMES, ALWAYS };
  private final HashMap<MethodReference, Frequency> usesFirstArgAsPropertyName_cache = HashMapFactory.make();
  
  /** Determine whether the method never/sometimes/always uses its first argument as a property name. */
  private Frequency usesFirstArgAsPropertyName(IMethod method) {
    MethodReference mref = method.getReference();
    if(method.getNumberOfParameters() < index)
      return Frequency.NEVER;
    Frequency f = usesFirstArgAsPropertyName_cache.get(mref);
    if(f != null)
      return f;
    boolean usedAsPropertyName = false, usedAsSomethingElse = false;
    DefUse du = cache.getDefUse(cache.getIR(method));
    for(SSAInstruction use : Iterator2Iterable.make(du.getUses(index+1))) {
      if(use instanceof ReflectiveMemberAccess) {
        ReflectiveMemberAccess rma = (ReflectiveMemberAccess)use;
        if(rma.getMemberRef() == index+1) {
          usedAsPropertyName = true;
          continue;
        }
      } else if(use instanceof AstIsDefinedInstruction) {
        AstIsDefinedInstruction aidi = (AstIsDefinedInstruction)use;
        if(aidi.getNumberOfUses() > 1 && aidi.getUse(1) == index+1) {
          usedAsPropertyName = true;
          continue;
        }
      }
      usedAsSomethingElse = true;
    }
    if(!usedAsPropertyName)
      f = Frequency.NEVER;
    else if(usedAsSomethingElse)
      f = Frequency.SOMETIMES;
    else
      f = Frequency.ALWAYS;
    usesFirstArgAsPropertyName_cache.put(mref, f);
    return f;
  }

  public Context getCalleeTarget(CGNode caller, CallSiteReference site, IMethod callee, final InstanceKey[] receiver) {
    Context baseContext = base.getCalleeTarget(caller, site, callee, receiver);
    
    if(receiver.length > index) {
      Frequency f = usesFirstArgAsPropertyName(callee);
      if(f == Frequency.ALWAYS|| f == Frequency.SOMETIMES) {
        if(receiver[index] == null) {
          IClass undef = caller.getClassHierarchy().lookupClass(JavaScriptTypes.Undefined);
          return new PropNameContext(baseContext, new ConcreteTypeKey(undef));
        } else {
          return new PropNameContext(baseContext, receiver[index]);
        }
      }
    }
    
    if (PROPNAME_MARKER.equals(caller.getContext().get(PROPNAME_KEY))) {
      if (!identifyDependentParameters(caller, site).isEmpty()) {
        // use a MarkerForInContext to clone based on the InstanceKey used in the caller context
        // TODO the cast below isn't safe; fix
        InstanceKey callerIk = ((PropNameContext)caller.getContext()).getInstanceKey();
        return new MarkerForInContext(baseContext, callerIk);
      } else {
        return baseContext;
      }
    } 
    return baseContext;
  }
  

  public IntSet getRelevantParameters(CGNode caller, CallSiteReference site) {
    if (caller.getIR().getCalls(site)[0].getNumberOfUses() > index) {
      return IntSetUtil.make(new int[]{index}).union(base.getRelevantParameters(caller, site));
    } else {
      return base.getRelevantParameters(caller, site);
    }
  }

}