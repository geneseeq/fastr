/*
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.r.runtime.ffi.jnr;

import java.io.*;
import java.nio.*;
import java.util.*;

import jnr.constants.platform.*;
import jnr.ffi.*;
import jnr.ffi.annotations.*;
import jnr.posix.*;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.r.runtime.*;
import com.oracle.truffle.r.runtime.ffi.*;
import com.oracle.truffle.r.runtime.ffi.DLL.DLLInfo;

/**
 * JNR/JNI-based factory.
 */
public class JNR_RFFIFactory extends RFFIFactory implements RFFI, BaseRFFI, StatsRFFI, RApplRFFI, LapackRFFI, UserRngRFFI, PCRERFFI {

    public JNR_RFFIFactory() {
    }

    @Override
    protected void initialize() {
        // This must load early as package libraries reference symbols in it.
        getCallRFFI();
    }

    private static byte[] wrapChar(char v) {
        return new byte[]{(byte) v};
    }

    private static int[] wrapInt(int v) {
        return new int[]{v};
    }

    private static double[] wrapDouble(double v) {
        return new double[]{v};
    }

    // Base

    @Override
    public BaseRFFI getBaseRFFI() {
        return this;
    }

    /**
     * Functions missing from JNR POSIX.
     */
    public interface LibCX {
        int getwd(@Out byte[] path);

        long mkdtemp(@In @Out ByteBuffer template);

        long strtol(@In String dir, @In String end, int base);

        int uname(@In long[] utsname);
    }

    private static class LibCXProvider {
        private static LibCX libcx;

        @TruffleBoundary
        private static LibCX createAndLoadLib() {
            return LibraryLoader.create(LibCX.class).load("c");
        }

        static LibCX libcx() {
            if (libcx == null) {
                libcx = createAndLoadLib();
            }
            return libcx;
        }
    }

    private static LibCX libcx() {
        return LibCXProvider.libcx();
    }

    protected POSIX posix;

    @Override
    protected RFFI createRFFI() {
        return this;
    }

    protected POSIX posix() {
        if (posix == null) {
            posix = POSIXFactory.getPOSIX();
        }
        return posix;
    }

    public int getpid() {
        return posix().getpid();
    }

    public int setwd(String dir) {
        return posix().chdir(dir);
    }

    @TruffleBoundary
    public String getwd() {
        byte[] buf = new byte[4096];
        int rc = libcx().getwd(buf);
        if (rc == 0) {
            return null;
        } else {
            int i = 0;
            while (buf[i] != 0 && i < buf.length) {
                i++;
            }
            return new String(buf, 0, i);
        }
    }

    public String readlink(String path) throws IOException {
        String s = posix().readlink(path);
        if (s == null) {
            int n = posix().errno();
            if (n == Errno.EINVAL.intValue()) {
                // not a link
            } else {
                // some other error
                throw ioex(Errno.valueOf(n).description());
            }
        }
        return s;
    }

    public String mkdtemp(String template) {
        ByteBuffer bb = ByteBuffer.wrap(template.getBytes());
        long result = libcx().mkdtemp(bb);
        if (result == 0) {
            return null;
        } else {
            return new String(bb.array());
        }
    }

    public void mkdir(String dir, int mode) throws IOException {
        try {
            posix().mkdir(dir, mode);
        } catch (RuntimeException ex) {
            throw ioex(Errno.valueOf(posix().errno()).description());
        }
    }

    public int chmod(String path, int mode) {
        return posix().chmod(path, mode);
    }

    public long strtol(String s, int base) throws IllegalArgumentException {
        posix().errno(0);
        long result = libcx().strtol(s, null, base);
        int e = posix().errno();
        if (e != 0) {
            throw new IllegalArgumentException(Errno.valueOf(e).description());
        } else {
            return result;
        }
    }

    public Object dlopen(String path, boolean local, boolean now) {
        int flags = (local ? com.kenai.jffi.Library.LOCAL : com.kenai.jffi.Library.GLOBAL) | (now ? com.kenai.jffi.Library.NOW : com.kenai.jffi.Library.LAZY);
        return com.kenai.jffi.Library.getCachedInstance(path, flags);
    }

    public long dlsym(Object handle, String symbol) {
        return ((com.kenai.jffi.Library) handle).getSymbolAddress(symbol);
    }

    public int dlclose(Object handle) {
        // TODO JNR provides no (public) way to close a library
        return 1;
    }

    public String dlerror() {
        return com.kenai.jffi.Library.getLastError();
    }

    public UtsName uname() {
        return JNIUtsName.get();
    }

    public ArrayList<String> glob(String pattern) {
        return JNIGlob.glob(pattern);
    }

    /*
     * Lapack methods.
     */

    @Override
    public LapackRFFI getLapackRFFI() {
        return this;
    }

    /**
     * Fortran does call by reference for everything, which we handle with arrays. Evidently, this
     * is not as efficient as it could be. This implementation assumes a single-threaded
     * environment.
     */
    public interface Lapack {
        // Checkstyle: stop method name
        void ilaver_(@Out int[] major, @Out int[] minor, @Out int[] patch);

        void dgeev_(@In byte[] jobVL, @In byte[] jobVR, @In int[] n, @In double[] a, @In int[] lda, @Out double[] wr, @Out double[] wi, @Out double[] vl, @In int[] ldvl, @Out double[] vr,
                        @In int[] ldvr, @Out double[] work, @In int[] lwork, @Out int[] info);

        void dgeqp3_(@In int[] m, @In int[] n, double[] a, @In int[] lda, int[] jpvt, @Out double[] tau, @Out double[] work, @In int[] lwork, @Out int[] info);

        void dormqr_(@In byte[] side, @In byte[] trans, @In int[] m, @In int[] n, @In int[] k, @In double[] a, @In int[] lda, @In double[] tau, double[] c, @In int[] ldc, @Out double[] work,
                        @In int[] lwork, @Out int[] info);

        void dtrtrs_(@In byte[] uplo, @In byte[] trans, @In byte[] diag, @In int[] n, @In int[] nrhs, @In double[] a, @In int[] lda, double[] b, @In int[] ldb, @Out int[] info);

        void dgetrf_(@In int[] m, @In int[] n, double[] a, @In int[] lda, @Out int[] ipiv, @Out int[] info);

        void dpotrf_(@In byte[] uplo, @In int[] n, double[] a, @In int[] lda, @Out int[] info);

        void dpstrf_(@In byte[] uplo, @In int[] n, double[] a, @In int[] lda, @Out int[] piv, @Out int[] rank, @In double[] tol, @Out double[] work, @Out int[] info);
    }

    private static class LapackProvider {
        private static Lapack lapack;

        @TruffleBoundary
        private static Lapack createAndLoadLib() {
            return LibraryLoader.create(Lapack.class).load("Rlapack");
        }

        static Lapack lapack() {
            if (lapack == null) {
                lapack = createAndLoadLib();
            }
            return lapack;
        }
    }

    private static Lapack lapack() {
        return LapackProvider.lapack();
    }

    @TruffleBoundary
    public void ilaver(int[] version) {
        int[] major = new int[1];
        int[] minor = new int[1];
        int[] patch = new int[1];
        lapack().ilaver_(major, minor, patch);
        version[0] = major[0];
        version[1] = minor[0];
        version[2] = patch[0];
    }

    @TruffleBoundary
    public int dgeev(char jobVL, char jobVR, int n, double[] a, int lda, double[] wr, double[] wi, double[] vl, int ldvl, double[] vr, int ldvr, double[] work, int lwork) {
        int[] info = new int[1];
        lapack().dgeev_(wrapChar(jobVL), wrapChar(jobVR), wrapInt(n), a, wrapInt(lda), wr, wi, vl, wrapInt(ldvl), vr, wrapInt(ldvr), work, wrapInt(lwork), info);
        return info[0];
    }

    @TruffleBoundary
    public int dgeqp3(int m, int n, double[] a, int lda, int[] jpvt, double[] tau, double[] work, int lwork) {
        int[] info = new int[1];
        lapack().dgeqp3_(wrapInt(m), wrapInt(n), a, wrapInt(lda), jpvt, tau, work, wrapInt(lwork), info);
        return info[0];
    }

    @TruffleBoundary
    public int dormqr(char side, char trans, int m, int n, int k, double[] a, int lda, double[] tau, double[] c, int ldc, double[] work, int lwork) {
        int[] info = new int[1];
        lapack().dormqr_(wrapChar(side), wrapChar(trans), wrapInt(m), wrapInt(n), wrapInt(k), a, wrapInt(lda), tau, c, wrapInt(ldc), work, wrapInt(lwork), info);
        return info[0];
    }

    @TruffleBoundary
    public int dtrtrs(char uplo, char trans, char diag, int n, int nrhs, double[] a, int lda, double[] b, int ldb) {
        int[] info = new int[1];
        lapack().dtrtrs_(wrapChar(uplo), wrapChar(trans), wrapChar(diag), wrapInt(n), wrapInt(nrhs), a, wrapInt(lda), b, wrapInt(ldb), info);
        return info[0];
    }

    @TruffleBoundary
    public int dgetrf(int m, int n, double[] a, int lda, int[] ipiv) {
        int[] info = new int[1];
        lapack().dgetrf_(wrapInt(m), wrapInt(n), a, wrapInt(lda), ipiv, info);
        return info[0];
    }

    @TruffleBoundary
    public int dpotrf(char uplo, int n, double[] a, int lda) {
        int[] info = new int[1];
        lapack().dpotrf_(wrapChar(uplo), wrapInt(n), a, wrapInt(lda), info);
        return info[0];
    }

    @TruffleBoundary
    public int dpstrf(char uplo, int n, double[] a, int lda, int[] piv, int[] rank, double tol, double[] work) {
        int[] info = new int[1];
        lapack().dpstrf_(wrapChar(uplo), wrapInt(n), a, wrapInt(lda), piv, rank, wrapDouble(tol), work, info);
        return info[0];
    }

    /*
     * Linpack (libappl) functions
     */

    @Override
    public RApplRFFI getRApplRFFI() {
        return this;
    }

    public interface Linpack {
        void dqrdc2_(double[] x, @In int[] ldx, @In int[] n, @In int[] p, @In double[] tol, int[] rank, double[] qraux, int[] pivot, @Out double[] work);

        void dqrcf_(double[] x, @In int[] n, @In int[] k, double[] qraux, double[] y, @In int[] ny, double[] b, int[] info);
    }

    private static class LinpackProvider {
        private static Linpack linpack;

        @TruffleBoundary
        private static Linpack createAndLoadLib() {
            // need to load blas lib as Fortran functions in appl lib need it
            LibraryLoader.create(Linpack.class).load("Rblas");
            return LibraryLoader.create(Linpack.class).load("appl");
        }

        static Linpack linpack() {
            if (linpack == null) {
                linpack = createAndLoadLib();
            }
            return linpack;
        }
    }

    private static Linpack linpack() {
        return LinpackProvider.linpack();
    }

    @TruffleBoundary
    public void dqrdc2(double[] x, int ldx, int n, int p, double tol, int[] rank, double[] qraux, int[] pivot, double[] work) {
        linpack().dqrdc2_(x, wrapInt(ldx), wrapInt(n), wrapInt(p), wrapDouble(tol), rank, qraux, pivot, work);
    }

    @TruffleBoundary
    public void dqrcf(double[] x, int n, int k, double[] qraux, double[] y, int ny, double[] b, int[] info) {
        linpack().dqrcf_(x, wrapInt(n), wrapInt(k), qraux, y, wrapInt(ny), b, info);
    }

    // Stats functions

    @Override
    public StatsRFFI getStatsRFFI() {
        return this;
    }

    public interface Stats {
        /*
         * TODO add @In/@Out to any arrays that are known to be either @In or @Out (default is
         * 
         * @Inout)
         */

        void fft_factor(@In int[] n, int[] pmaxf, int[] pmaxp);

        int fft_work(double[] a, @In int[] nseg, @In int[] n, @In int[] nspn, @In int[] isn, double[] work, int[] iwork);
    }

    private static class StatsProvider {
        private static Stats stats;

        @TruffleBoundary
        private static Stats createAndLoadLib() {
            // fft is in the stats package .so
            DLLInfo dllInfo = DLL.findLibraryContainingSymbol("fft");
            return LibraryLoader.create(Stats.class).load(dllInfo.path);
        }

        static Stats fft() {
            if (stats == null) {
                stats = createAndLoadLib();
            }
            return stats;
        }
    }

    private static Stats stats() {
        return StatsProvider.fft();
    }

    @TruffleBoundary
    public void fft_factor(int n, int[] pmaxf, int[] pmaxp) {
        stats().fft_factor(wrapInt(n), pmaxf, pmaxp);
    }

    @TruffleBoundary
    public int fft_work(double[] a, int nseg, int n, int nspn, int isn, double[] work, int[] iwork) {
        return stats().fft_work(a, wrapInt(nseg), wrapInt(n), wrapInt(nspn), wrapInt(isn), work, iwork);
    }

    /*
     * UserRng. This is a singleton instance, although the actual library may vary from run to run.
     */

    @Override
    public UserRngRFFI getUserRngRFFI() {
        return this;
    }

    public interface UserRng {
        void user_unif_init(@In int seed);

        Pointer user_unif_rand();

        Pointer user_unif_nseed();

        Pointer user_unif_seedloc();
    }

    private static class UserRngProvider {
        private static String libPath;
        private static UserRng userRng;

        UserRngProvider(String libPath) {
            UserRngProvider.libPath = libPath;
        }

        @TruffleBoundary
        private static UserRng createAndLoadLib() {
            return LibraryLoader.create(UserRng.class).load(libPath);
        }

        static UserRng userRng() {
            if (userRng == null) {
                userRng = createAndLoadLib();
            }
            return userRng;
        }
    }

    private static UserRng userRng() {
        return UserRngProvider.userRng();
    }

    @SuppressWarnings("unused")
    public void setLibrary(String path) {
        new UserRngProvider(path);

    }

    @TruffleBoundary
    public void init(int seed) {
        userRng().user_unif_init(seed);
    }

    @TruffleBoundary
    public double rand() {
        Pointer pDouble = userRng().user_unif_rand();
        return pDouble.getDouble(0);
    }

    @TruffleBoundary
    public int nSeed() {
        return userRng().user_unif_nseed().getInt(0);
    }

    @TruffleBoundary
    public void seeds(int[] n) {
        Pointer pInt = userRng().user_unif_seedloc();
        for (int i = 0; i < n.length; i++) {
            n[i] = pInt.getInt(i);
        }
    }

    /*
     * .C methods
     */

    private static CRFFI cRFFI;

    @Override
    public CRFFI getCRFFI() {
        if (cRFFI == null) {
            cRFFI = new CRFFI_JNR_Invoke();
        }
        return cRFFI;
    }

    /*
     * .C methods
     */

    private static CallRFFI callRFFI;

    @Override
    public CallRFFI getCallRFFI() {
        if (callRFFI == null) {
            callRFFI = new CallRFFIWithJNI();
        }
        return callRFFI;
    }

    // zip

    public interface Zip {
        int compress(@Out byte[] dest, long[] destlen, @In byte[] source, long sourcelen);

        int uncompress(@Out byte[] dest, long[] destlen, @In byte[] source, long sourcelen);
    }

    private static class ZipProvider {
        private static Zip zip;

        @TruffleBoundary
        private static Zip createAndLoadLib() {
            return LibraryLoader.create(Zip.class).load("z");
        }

        static Zip zip() {
            if (zip == null) {
                zip = createAndLoadLib();
            }
            return zip;
        }
    }

    private static Zip zip() {
        return ZipProvider.zip();
    }

    @TruffleBoundary
    public int compress(byte[] dest, long[] destlen, byte[] source) {
        return zip().compress(dest, destlen, source, source.length);
    }

    @TruffleBoundary
    public int uncompress(byte[] dest, long[] destlen, byte[] source) {
        return zip().uncompress(dest, destlen, source, source.length);
    }

    // PCRE

    @Override
    public PCRERFFI getPCRERFFI() {
        return this;
    }

    public interface PCRE {
        long pcre_maketables();

        long pcre_compile(String pattern, int options, @Out byte[] errorMessage, @Out int[] errOffset, long tables);

        int pcre_exec(long code, long extra, @In byte[] subject, int subjectLength, int startOffset, int options, @Out int[] ovector, int ovecSize);
    }

    private static class PCREProvider {
        private static PCRE pcre;

        @TruffleBoundary
        private static PCRE createAndLoadLib() {
            return LibraryLoader.create(PCRE.class).load("pcre");
        }

        static PCRE pcre() {
            if (pcre == null) {
                pcre = createAndLoadLib();
            }
            return pcre;
        }
    }

    private static PCRE pcre() {
        return PCREProvider.pcre();
    }

    public long maketables() {
        return pcre().pcre_maketables();
    }

    public Result compile(String pattern, int options, long tables) {
        int[] errOffset = new int[1];
        byte[] errorMessage = new byte[512];
        long result = pcre().pcre_compile(pattern, options, errorMessage, errOffset, tables);
        if (result == 0) {
            return new Result(result, new String(errorMessage), errOffset[0]);
        } else {
            return new Result(result, null, 0);
        }
    }

    public Result study(long code, int options) {
        throw RInternalError.unimplemented("pcre_study");
    }

    public int exec(long code, long extra, String subject, int offset, int options, int[] ovector) {
        return pcre().pcre_exec(code, extra, subject.getBytes(), subject.length(), offset, options, ovector, ovector.length);
    }

}
