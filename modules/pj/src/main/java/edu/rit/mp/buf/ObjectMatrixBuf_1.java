//******************************************************************************
//
// File:    ObjectMatrixBuf_1.java
// Package: edu.rit.mp.buf
// Unit:    Class edu.rit.mp.buf.ObjectMatrixBuf_1
//
// This Java source file is copyright (C) 2009 by Alan Kaminsky. All rights
// reserved. For further information, contact the author, Alan Kaminsky, at
// ark@cs.rit.edu.
//
// This Java source file is part of the Parallel Java Library ("PJ"). PJ is free
// software; you can redistribute it and/or modify it under the terms of the GNU
// General Public License as published by the Free Software Foundation; either
// version 3 of the License, or (at your option) any later version.
//
// PJ is distributed in the hope that it will be useful, but WITHOUT ANY
// WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
// A PARTICULAR PURPOSE. See the GNU General Public License for more details.
//
// Linking this library statically or dynamically with other modules is making a
// combined work based on this library. Thus, the terms and conditions of the GNU
// General Public License cover the whole combination.
//
// As a special exception, the copyright holders of this library give you
// permission to link this library with independent modules to produce an
// executable, regardless of the license terms of these independent modules, and
// to copy and distribute the resulting executable under terms of your choice,
// provided that you also meet, for each linked independent module, the terms
// and conditions of the license of that module. An independent module is a module
// which is not derived from or based on this library. If you modify this library,
// you may extend this exception to your version of the library, but you are not
// obligated to do so. If you do not wish to do so, delete this exception
// statement from your version.
//
// A copy of the GNU General Public License is provided in the file gpl.txt. You
// may also obtain a copy of the GNU General Public License on the World Wide
// Web at http://www.gnu.org/licenses/gpl.html.
//
//******************************************************************************
package edu.rit.mp.buf;

import edu.rit.mp.Buf;
import edu.rit.pj.reduction.ObjectOp;
import edu.rit.pj.reduction.Op;
import edu.rit.util.Range;

/**
 * Class ObjectMatrixBuf_1 provides a buffer for a matrix of object items sent
 * or received using the Message Protocol (MP). The matrix row and column
 * strides must both be 1. While an instance of class ObjectMatrixBuf_1 may be
 * constructed directly, normally you will use a factory method in class
 * {@linkplain edu.rit.mp.ObjectBuf ObjectBuf}. See that class for further
 * information.
 *
 * @param <T> Data type of the objects in the buffer.
 * @author Alan Kaminsky
 * @version 23-Mar-2009
 */
@SuppressWarnings("unchecked")
public class ObjectMatrixBuf_1<T>
        extends ObjectMatrixBuf<T> {

// Exported constructors.
    /**
     * Construct a new object matrix buffer. It is assumed that the rows and
     * columns of <code>theMatrix</code> are allocated and that each row of
     * <code>theMatrix</code> has the same number of columns.
     *
     * @param theMatrix Matrix.
     * @param theRowRange Range of rows to include. The stride is assumed to be
     * 1.
     * @param theColRange Range of columns to include. The stride is assumed to
     * be 1.
     */
    public ObjectMatrixBuf_1(T[][] theMatrix,
            Range theRowRange,
            Range theColRange) {
        super(theMatrix, theRowRange, theColRange);
    }

// Exported operations.
    /**
     * {@inheritDoc}
     *
     * Obtain the given item from this buffer.
     * <P>
     * The <code>get()</code> method must not block the calling thread; if it does,
     * all message I/O in MP will be blocked.
     */
    public T get(int i) {
        return myMatrix[i2r(i) + myLowerRow][i2c(i) + myLowerCol];
    }

    /**
     * Store the given item in this buffer.
     * <P>
     * The <code>put()</code> method must not block the calling thread; if it does,
     * all message I/O in MP will be blocked.
     *
     * @param i Item index in the range 0 .. <code>length()</code>-1.
     * @param item Item to be stored at index <code>i</code>.
     */
    public void put(int i,
            T item) {
        myMatrix[i2r(i) + myLowerRow][i2c(i) + myLowerCol] = item;
        reset();
    }

    /**
     * {@inheritDoc}
     *
     * Create a buffer for performing parallel reduction using the given binary
     * operation. The results of the reduction are placed into this buffer.
     * @exception ClassCastException (unchecked exception) Thrown if this
     * buffer's element data type and the given binary operation's argument data
     * type are not the same.
     */
    public Buf getReductionBuf(Op op) {
        return new ObjectMatrixReductionBuf_1<T>(myMatrix, myRowRange, myColRange, (ObjectOp<T>) op, this);
    }

}
