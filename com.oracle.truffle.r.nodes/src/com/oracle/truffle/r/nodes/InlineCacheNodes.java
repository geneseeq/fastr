/*
 * Copyright (c) 2014, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.r.nodes;

import java.util.function.Function;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.r.nodes.InlineCacheNodesFactory.InlineCacheExpressionNodeGen;
import com.oracle.truffle.r.nodes.InlineCacheNodesFactory.InlineCachePromiseNodeGen;
import com.oracle.truffle.r.runtime.RInternalError;
import com.oracle.truffle.r.runtime.SubstituteVirtualFrame;
import com.oracle.truffle.r.runtime.context.Engine;
import com.oracle.truffle.r.runtime.context.RContext;
import com.oracle.truffle.r.runtime.data.RDataFactory;
import com.oracle.truffle.r.runtime.data.RLanguage;
import com.oracle.truffle.r.runtime.data.RPromise.Closure;
import com.oracle.truffle.r.runtime.nodes.RBaseNode;
import com.oracle.truffle.r.runtime.nodes.RNode;
import com.oracle.truffle.r.runtime.nodes.TruffleBiFunction;

/**
 * This node reifies a runtime object into the AST by creating nodes for frequently encountered
 * values. This can be used to bridge the gap between code as runtime data and executed code.
 */
public abstract class InlineCacheNodes {

    public abstract static class InlineCacheNode extends RBaseNode {
        protected final int maxPicDepth;

        public abstract Object execute(Frame frame, Object value);

        protected InlineCacheNode(int maxPicDepth) {
            this.maxPicDepth = maxPicDepth;
        }

        @SuppressWarnings("unused")
        @Specialization(limit = "maxPicDepth", guards = "value == cachedValue")
        protected Object doCached(Frame frame, Object value,
                        @Cached("value") Object cachedValue,
                        @Cached("createBinaryProfile()") ConditionProfile isVirtualFrameProfile,
                        @Cached("cache(cachedValue)") RNode reified,
                        @Cached("new(reified)") NodeInsertedClosure nodeInsertedClosure) {
            VirtualFrame vf;
            if (isVirtualFrameProfile.profile(frame instanceof VirtualFrame)) {
                vf = (VirtualFrame) frame;
            } else {
                vf = SubstituteVirtualFrame.create(frame.materialize());
            }

            // Use a closure to notify the root node that a new node has just been inserted. The
            // closure
            // is necessary to do the notification just once.
            nodeInsertedClosure.notifyNodeInserted();

            return reified.visibleExecute(vf);
        }

        protected RNode cache(@SuppressWarnings("unused") Object value) {
            throw RInternalError.shouldNotReachHere();
        }

        /**
         * Creates an inline cache.
         *
         * @param maxPicDepth maximum number of entries in the polymorphic inline cache
         * @param reify a function that turns the runtime value into an RNode
         * @param generic a function that will be used to evaluate the given value after the
         *            polymorphic inline cache has reached its maximum size
         */
        public static <T> InlineCacheNode createCache(int maxPicDepth, Function<T, RNode> reify, TruffleBiFunction<Frame, T, Object> generic) {
            throw RInternalError.shouldNotReachHere("deprecated");
        }

        /**
         * Creates an inline cache that will execute runtime expression given to it as RNodes by using a
         * PIC and falling back to {@link Engine#eval(RLanguage, MaterializedFrame)}.
         *
         * @param maxPicDepth maximum number of entries in the polymorphic inline cache
         */
        public static <F extends Frame> InlineCacheNode createExpression(int maxPicDepth) {
            return InlineCacheExpressionNode.create(maxPicDepth);
        }

        /**
         * Creates an inline cache that will execute promises closures by using a PIC and falling back
         * to {@link InlineCacheNode#evalPromise(Frame, Closure)}.
         *
         * @param maxPicDepth maximum number of entries in the polymorphic inline cache
         */
        public static <F extends Frame> InlineCacheNode createPromise(int maxPicDepth) {
            return InlineCachePromiseNode.create(maxPicDepth);
        }

        @TruffleBoundary
        protected static Object evalPromise(Frame frame, Closure closure) {
            return closure.eval(frame.materialize());
        }

        protected class NodeInsertedClosure {
            private final Node n;
            @CompilationFinal boolean notified;

            public NodeInsertedClosure(Node n) {
                this.n = n;
            }

            void notifyNodeInserted() {
                if (!notified) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    InlineCacheNode.this.notifyInserted(n);
                    notified = true;
                }
            }
        }

    }

    abstract static class InlineCachePromiseNode extends InlineCacheNode {

        InlineCachePromiseNode(int maxPicDepth) {
            super(maxPicDepth);
        }

        @Specialization(replaces = "doCached")
        protected Object doGeneric(Frame frame, Object value) {
            return evalPromise(frame, (Closure) value);
        }

        @Override
        protected RNode cache(Object value) {
            return RASTUtils.cloneNode((RNode) ((Closure) value).getExpr());
        }

        public static InlineCachePromiseNode create(int maxPicDepth) {
            return InlineCachePromiseNodeGen.create(maxPicDepth);
        }

    }

    abstract static class InlineCacheExpressionNode extends InlineCacheNode {

        InlineCacheExpressionNode(int maxPicDepth) {
            super(maxPicDepth);
        }

        @Specialization(replaces = "doCached")
        protected Object doGeneric(Frame frame, Object value) {
            return RContext.getEngine().eval(RDataFactory.createLanguage((RNode) value), frame.materialize());
        }

        @Override
        protected RNode cache(Object value) {
            return RASTUtils.cloneNode((RNode) value);
        }

        public static InlineCacheExpressionNode create(int maxPicDepth) {
            return InlineCacheExpressionNodeGen.create(maxPicDepth);
        }

    }
}
