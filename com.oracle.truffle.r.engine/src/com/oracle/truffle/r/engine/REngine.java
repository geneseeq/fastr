/*
 * Copyright (c) 2013, 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.r.engine;

import java.io.*;
import java.util.*;

import org.antlr.runtime.*;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.SlowPath;
import com.oracle.truffle.api.*;
import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.nodes.*;
import com.oracle.truffle.api.source.*;
import com.oracle.truffle.r.nodes.*;
import com.oracle.truffle.r.nodes.access.*;
import com.oracle.truffle.r.nodes.builtin.*;
import com.oracle.truffle.r.nodes.function.*;
import com.oracle.truffle.r.options.*;
import com.oracle.truffle.r.parser.*;
import com.oracle.truffle.r.parser.ast.*;
import com.oracle.truffle.r.runtime.*;
import com.oracle.truffle.r.runtime.RContext.ConsoleHandler;
import com.oracle.truffle.r.runtime.data.*;
import com.oracle.truffle.r.runtime.data.RPromise.Closure;
import com.oracle.truffle.r.runtime.data.RPromise.PromiseProfile;
import com.oracle.truffle.r.runtime.env.*;
import com.oracle.truffle.r.runtime.env.REnvironment.PutException;
import com.oracle.truffle.r.runtime.ffi.*;
import com.oracle.truffle.r.runtime.rng.*;

/**
 * The engine for the FastR implementation. Handles parsing and evaluation. There is exactly one
 * instance of this class, stored in {link #singleton}.
 */
public final class REngine implements RContext.Engine {

    private static final REngine singleton = new REngine();

    @CompilationFinal private boolean crashOnFatalError;
    @CompilationFinal private long startTime;
    @CompilationFinal private long[] childTimes;
    @CompilationFinal private RContext context;
    @CompilationFinal private RBuiltinLookup builtinLookup;
    @CompilationFinal private RFunction evalFunction;

    private REngine() {
    }

    /**
     * Initialize the engine.
     *
     * @param commandArgs
     * @param consoleHandler for console input/output
     * @param crashOnFatalErrorArg if {@code true} any unhandled exception will terminate the
     *            process.
     * @return a {@link VirtualFrame} that can be passed to
     *         {@link #parseAndEval(String, String, VirtualFrame, REnvironment, boolean, boolean)}
     */
    public static VirtualFrame initialize(String[] commandArgs, ConsoleHandler consoleHandler, boolean crashOnFatalErrorArg, boolean headless) {
        singleton.startTime = System.nanoTime();
        singleton.childTimes = new long[]{0, 0};
        Locale.setDefault(Locale.ROOT);
        FastROptions.initialize();
        Load_RFFIFactory.initialize();
        RPerfAnalysis.initialize();
        singleton.crashOnFatalError = crashOnFatalErrorArg;
        singleton.builtinLookup = RBuiltinPackages.getInstance();
        singleton.context = RContext.setRuntimeState(singleton, commandArgs, consoleHandler, headless);
        VirtualFrame globalFrame = RRuntime.createNonFunctionFrame();
        VirtualFrame baseFrame = RRuntime.createNonFunctionFrame();
        REnvironment.baseInitialize(globalFrame, baseFrame);
        singleton.evalFunction = singleton.lookupBuiltin("eval");
        RPackageVariables.initializeBase();
        RVersionInfo.initialize();
        RAccuracyInfo.initialize();
        RRNG.initialize();
        TempDirPath.initialize();
        LibPaths.initialize();
        ROptions.initialize();
        RProfile.initialize();
        // eval the system profile
        singleton.parseAndEval("<system_profile>", RProfile.systemProfile(), baseFrame, REnvironment.baseEnv(), false, false);
        REnvironment.packagesInitialize(RPackages.initialize());
        RPackageVariables.initialize(); // TODO replace with R code
        String siteProfile = RProfile.siteProfile();
        if (siteProfile != null) {
            singleton.parseAndEval("<site_profile>", siteProfile, baseFrame, REnvironment.baseEnv(), false, false);
        }
        String userProfile = RProfile.userProfile();
        if (userProfile != null) {
            singleton.parseAndEval("<user_profile>", userProfile, globalFrame, REnvironment.globalEnv(), false, false);
        }
        return globalFrame;
    }

    public static REngine getInstance() {
        return singleton;
    }

    public void loadDefaultPackage(String name, VirtualFrame frame, REnvironment envForFrame) {
        RBuiltinPackages.load(name, frame, envForFrame);
    }

    public RFunction lookupBuiltin(String name) {
        return builtinLookup.lookup(name);
    }

    public long elapsedTimeInNanos() {
        return System.nanoTime() - startTime;
    }

    public long[] childTimesInNanos() {
        return childTimes;
    }

    public Object parseAndEval(String sourceDesc, String rscript, VirtualFrame frame, REnvironment envForFrame, boolean printResult, boolean allowIncompleteSource) {
        return parseAndEvalImpl(new ANTLRStringStream(rscript), Source.asPseudoFile(rscript, sourceDesc), frame, printResult, allowIncompleteSource);
    }

    public Object parseAndEvalTest(String rscript, boolean printResult) {
        VirtualFrame frame = RRuntime.createNonFunctionFrame();
        REnvironment.resetForTest(frame);
        return parseAndEvalImpl(new ANTLRStringStream(rscript), Source.asPseudoFile(rscript, "<test_input>"), frame, printResult, false);
    }

    public class ParseException extends Exception {
        private static final long serialVersionUID = 1L;

        public ParseException(String msg) {
            super(msg);
        }
    }

    public RExpression parse(String rscript) throws RContext.Engine.ParseException {
        try {
            Sequence seq = (Sequence) ParseUtil.parseAST(new ANTLRStringStream(rscript), Source.asPseudoFile(rscript, "<parse_input>"));
            ASTNode[] exprs = seq.getExpressions();
            Object[] data = new Object[exprs.length];
            for (int i = 0; i < exprs.length; i++) {
                data[i] = RDataFactory.createLanguage(transform(exprs[i], REnvironment.emptyEnv()));
            }
            return RDataFactory.createExpression(RDataFactory.createList(data));
        } catch (RecognitionException ex) {
            throw new RContext.Engine.ParseException(ex.getMessage());
        }
    }

    public Object eval(RFunction function, RExpression expr, REnvironment envir, REnvironment enclos) throws PutException {
        Object result = null;
        RFunction ffunction = function;
        if (ffunction == null) {
            ffunction = evalFunction;
        }
        for (int i = 0; i < expr.getLength(); i++) {
            RLanguage lang = (RLanguage) expr.getDataAt(i);
            result = eval(ffunction, (RNode) lang.getRep(), envir, enclos);
        }
        return result;
    }

    public Object eval(RFunction function, RLanguage expr, REnvironment envir, REnvironment enclos) throws PutException {
        RFunction ffunction = function;
        if (ffunction == null) {
            ffunction = evalFunction;
        }
        return eval(ffunction, (RNode) expr.getRep(), envir, enclos);
    }

    public Object eval(RExpression expr, VirtualFrame frame) {
        Object result = null;
        for (int i = 0; i < expr.getLength(); i++) {
            result = expr.getDataAt(i);
            if (result instanceof RLanguage) {
                RLanguage lang = (RLanguage) result;
                result = eval(lang, frame);
            }
        }
        return result;
    }

    private static final String EVAL_FUNCTION_NAME = "<eval wrapper>";

    public Object eval(RLanguage expr, VirtualFrame frame) {
        RNode n = expr.getType() == RLanguage.Type.RNODE ? (RNode) expr.getRep() : makeCallNode(expr);
        RootCallTarget callTarget = doMakeCallTarget(n, EVAL_FUNCTION_NAME);
        return runCall(callTarget, frame, false, false);
    }

    @SlowPath
    private RCallNode makeCallNode(RLanguage expr) {
        RStringVector names = expr.getList().getNames() == RNull.instance ? null : (RStringVector) expr.getList().getNames();

        int argLength = expr.getLength() - 1;
        RNode[] args = new RNode[argLength];
        String[] argNames = new String[argLength];

        for (int i = 0; i < argLength; i++) {
            Object a = expr.getDataAt(i + 1);
            if (a instanceof RSymbol) {
                args[i] = ReadVariableNode.create(((RSymbol) a).getName(), RRuntime.TYPE_ANY, false, true, false, true);
            } else if (a instanceof RLanguage) {
                RLanguage l = (RLanguage) a;
                if (l.getType() == RLanguage.Type.RNODE) {
                    args[i] = (RNode) l.getRep();
                } else {
                    args[i] = makeCallNode(l);
                }
            } else if (a instanceof RPromise) {
                // TODO: flatten nested promises?
                args[i] = ((WrapArgumentNode) ((RPromise) a).getRep()).getOperand();
            } else {
                args[i] = ConstantNode.create(a);
            }
            if (names != null && !names.getDataAt(i + 1).equals(RRuntime.NAMES_ATTR_EMPTY_VALUE)) {
                argNames[i] = names.getDataAt(i + 1);
            }
        }

        // TODO: handle replacement calls
        boolean isReplacement = false;
        final CallArgumentsNode callArgsNode = CallArgumentsNode.create(!isReplacement, false, args, argNames);

        if (expr.getDataAt(0) instanceof RSymbol) {
            RSymbol funcName = (RSymbol) expr.getDataAt(0);
            // TODO: source section?
            return RCallNode.createCall(null, ReadVariableNode.create(funcName.getName(), RRuntime.TYPE_FUNCTION, false, true, false, true), callArgsNode);
        } else {
            return RCallNode.createStaticCall(null, (RFunction) expr.getDataAt(0), callArgsNode);
        }
    }

    /**
     * @param function
     * @param exprRep
     * @param envir
     * @param enclos
     * @return @see
     *         {@link #eval(RFunction, RootCallTarget, SourceSection, REnvironment, REnvironment)}
     * @throws PutException
     */
    private static Object eval(RFunction function, RNode exprRep, REnvironment envir, REnvironment enclos) throws PutException {
        RootCallTarget callTarget = doMakeCallTarget(exprRep, EVAL_FUNCTION_NAME);
        SourceSection callSrc = RArguments.getCallSourceSection(envir.getFrame());
        return eval(function, callTarget, callSrc, envir, enclos);
    }

    /**
     * This is tricky because the {@link Frame} "f" associated with {@code envir} has been
     * materialized so we can't evaluate in it directly. Instead we create a new
     * {@link VirtualEvalFrame} that behaves like "f" (delegates most calls to it) but has a
     * slightly changed arguments array.
     *
     * N.B. The implementation should do its utmost to avoid calling this method as it is inherently
     * inefficient. In particular, in the case where a {@link VirtualFrame} is available, then the
     * {@code eval} methods that take such a {@link VirtualFrame} should be used in preference.
     *
     */
    @SuppressWarnings("unused")
    private static Object eval(RFunction function, RootCallTarget callTarget, SourceSection callSrc, REnvironment envir, REnvironment enclos) throws PutException {
        MaterializedFrame envFrame = envir.getFrame();
        // Here we create fake frame that wraps the original frame's context and has an only
        // slightly changed arguments array (functio and callSrc).
        VirtualFrame vFrame = VirtualEvalFrame.create(envFrame, function, callSrc);
        return runCall(callTarget, vFrame, false, false);
    }

    public Object evalPromise(RPromise promise, VirtualFrame frame) throws RError {
        return runCall(promise.getClosure().getCallTarget(), frame, false, false);
    }

    public Object evalPromise(RPromise promise, SourceSection callSrc) throws RError {
        // have to do the full out eval
        try {
            REnvironment env = promise.getEnv();
            assert env != null;
            Closure closure = promise.getClosure();
            return eval(lookupBuiltin("eval"), closure.getCallTarget(), callSrc, env, null);
        } catch (PutException ex) {
            // TODO a new, rather unlikely, error
            assert false;
            return null;
        }
    }

    private static Object parseAndEvalImpl(ANTLRStringStream stream, Source source, VirtualFrame frame, boolean printResult, boolean allowIncompleteSource) {
        try {
            RootCallTarget callTarget = doMakeCallTarget(parseToRNode(stream, source), "<repl wrapper>");
            Object result = runCall(callTarget, frame, printResult, true);
            return result;
        } catch (NoViableAltException | MismatchedTokenException e) {
            if (e.token.getType() == Token.EOF && allowIncompleteSource) {
                // the parser got stuck at the eof, request another line
                return INCOMPLETE_SOURCE;
            }
            String line = source.getCode(e.line);
            String message = "Error: unexpected '" + e.token.getText() + "' in \"" + line.substring(0, e.charPositionInLine + 1) + "\"";
            singleton.context.getConsoleHandler().println(source.getLineCount() == 1 ? message : (message + " (line " + e.line + ")"));
            return null;
        } catch (RError e) {
            singleton.context.getConsoleHandler().println(e.getMessage());
            return null;
        } catch (RecognitionException | RuntimeException e) {
            singleton.context.getConsoleHandler().println("Exception while parsing: " + e);
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Parses a text stream into a Truffle AST.
     *
     * @param stream
     * @param source
     * @return the root node of the Truffle AST
     * @throws RecognitionException on parse error
     */
    private static RNode parseToRNode(ANTLRStringStream stream, Source source) throws RecognitionException {
        return transform(ParseUtil.parseAST(stream, source), REnvironment.globalEnv());
    }

    /**
     * Transforms an AST produced by the parser into a Truffle AST.
     *
     * @param astNode parser AST instance
     * @param environment the lexically enclosing environment that will be associated with top-level
     *            function definitions in {@code astNode}
     * @return the root node of the Truffle AST
     */
    private static RNode transform(ASTNode astNode, REnvironment environment) {
        RTruffleVisitor transform = new RTruffleVisitor(environment);
        RNode result = transform.transform(astNode);
        return result;
    }

    /**
     * Wraps the Truffle AST in {@code node} in an anonymous function and returns a
     * {@link RootCallTarget} for it. We define the
     * {@link com.oracle.truffle.r.runtime.env.REnvironment.FunctionDefinition} environment to have
     * the {@link REnvironment#emptyEnv()} as parent, so it is note scoped relative to any existing
     * environments, i.e. is truly anonymous.
     *
     * N.B. For certain expressions, there might be some value in enclosing the wrapper function in
     * a specific lexical scope. E.g., as a way to access names in the expression known to be
     * defined in that scope.
     *
     * @param body The AST for the body of the wrapper, i.e., the expression being evaluated.
     */
    @Override
    public RootCallTarget makeCallTarget(Object body, String funName) {
        assert body instanceof RNode;
        return doMakeCallTarget((RNode) body, funName);
    }

    /**
     * @param body
     * @return {@link #makeCallTarget(Object, String)}
     */
    @SlowPath
    private static RootCallTarget doMakeCallTarget(RNode body, String funName) {
        REnvironment.FunctionDefinition rootNodeEnvironment = new REnvironment.FunctionDefinition(REnvironment.emptyEnv());
        FunctionDefinitionNode rootNode = new FunctionDefinitionNode(null, rootNodeEnvironment, body, FormalArguments.NO_ARGS, funName, true, true);
        RootCallTarget callTarget = Truffle.getRuntime().createCallTarget(rootNode);
        return callTarget;
    }

    /**
     * Execute {@code callTarget} in {@code frame}, optionally printing any result. N.B.
     * {@code callTarget.call} will create a new {@link VirtualFrame} called, say, {@code newFrame},
     * in which to execute the (anonymous) {@link FunctionDefinitionNode} associated with
     * {@code callTarget}. When execution reaches {@link FunctionDefinitionNode#execute},
     * {@code frame} will be accessible via {@code newFrame.getArguments()[0]}, and the execution
     * will continue using {@code frame}.
     */
    private static Object runCall(RootCallTarget callTarget, VirtualFrame frame, boolean printResult, boolean topLevel) {
        Object result = null;
        try {
            try {
                // FIXME: callTargets should only be called via Direct/IndirectCallNode
                result = callTarget.call(frame.materialize());
            } catch (ControlFlowException cfe) {
                throw RError.error(RError.Message.NO_LOOP_FOR_BREAK_NEXT);
            }
            if (printResult) {
                printResult(result);
            }
            reportWarnings(false);
        } catch (RError e) {
            if (topLevel) {
                singleton.printRError(e);
            } else {
                throw e;
            }
        } catch (Throwable e) {
            reportImplementationError(e);
        }
        return result;
    }

    private static final PromiseProfile globalPromiseProfile = new PromiseProfile();

    @SlowPath
    private static void printResult(Object result) {
        if (RContext.isVisible()) {
            // TODO cache this
            Object resultValue = RPromise.checkEvaluate(null, result, globalPromiseProfile);
            RFunction function = (RFunction) REnvironment.baseEnv().get("print");
            function.getTarget().call(RArguments.create(function, null, new Object[]{resultValue, RMissing.instance}));
        }
    }

    @SlowPath
    public void printRError(RError e) {
        String es = e.toString();
        if (!es.isEmpty()) {
            context.getConsoleHandler().printErrorln(e.toString());
        }
        reportWarnings(true);
    }

    @SlowPath
    private static void reportImplementationError(Throwable e) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        e.printStackTrace(new PrintStream(out));
        singleton.context.getConsoleHandler().printErrorln(RRuntime.toString(out));
        // R suicide, unless, e.g., we are running units tests.
        // We don't call quit as the system is broken.
        if (singleton.crashOnFatalError) {
            Utils.exit(2);
        }
    }

    @SlowPath
    private static void reportWarnings(boolean inAddition) {
        List<String> evalWarnings = singleton.context.extractEvalWarnings();
        ConsoleHandler consoleHandler = singleton.context.getConsoleHandler();
        // GnuR outputs warnings to the stderr, so we do too
        if (evalWarnings != null && evalWarnings.size() > 0) {
            if (inAddition) {
                consoleHandler.printError("In addition: ");
            }
            if (evalWarnings.size() == 1) {
                consoleHandler.printErrorln("Warning message:");
                consoleHandler.printErrorln(evalWarnings.get(0));
            } else {
                consoleHandler.printErrorln("Warning messages:");
                for (int i = 0; i < evalWarnings.size(); i++) {
                    consoleHandler.printErrorln((i + 1) + ":");
                    consoleHandler.printErrorln("  " + evalWarnings.get(i));
                }
            }
        }
    }

}
