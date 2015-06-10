/*
 * This material is distributed under the GNU General Public License
 * Version 2. You may review the terms of this license at
 * http://www.gnu.org/licenses/gpl-2.0.html
 *
 * Copyright (c) 2014, Purdue University
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates
 *
 * All rights reserved.
 */
package com.oracle.truffle.r.test.builtins;

import org.junit.*;

import com.oracle.truffle.r.test.*;

// Checkstyle: stop line length check
public class TestBuiltin_intToBits extends TestBase {

    @Test
    public void testintToBits1() {
        assertEval(Ignored.Unknown, "argv <- list(list()); .Internal(intToBits(argv[[1]]))");
    }

    @Test
    public void testintToBits2() {
        assertEval(Ignored.Unknown, "argv <- list(NULL); .Internal(intToBits(argv[[1]]))");
    }
}