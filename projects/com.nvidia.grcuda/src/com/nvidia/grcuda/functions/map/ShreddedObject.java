/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *  * Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *  * Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *  * Neither the name of NVIDIA CORPORATION nor the names of its
 *    contributors may be used to endorse or promote products derived
 *    from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS ``AS IS'' AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
 * OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.nvidia.grcuda.functions.map;

import com.nvidia.grcuda.DeviceArray.MemberSet;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

@ExportLibrary(InteropLibrary.class)
final class ShreddedObject implements TruffleObject {

    private static final MemberSet MEMBERS = new MemberSet();

    private final Object delegate;

    ShreddedObject(Object delegate) {
        this.delegate = delegate;
    }

    @ExportMessage
    @SuppressWarnings("static-method")
    boolean hasMembers() {
        return true;
    }

    @SuppressWarnings("static-method")
    @ExportMessage
    Object getMembers(@SuppressWarnings("unused") boolean includeInternal) {
        return MEMBERS;
    }

    @SuppressWarnings("static-method")
    @ExportMessage
    boolean isMemberReadable(@SuppressWarnings("unused") String member) {
        return true;
    }

    @ExportMessage
    @TruffleBoundary
    public Object readMember(String member,
                    @CachedLibrary(limit = "2") InteropLibrary memberInterop) {
        if (memberInterop.isMemberReadable(delegate, member)) {
            try {
                return memberInterop.readMember(delegate, member);
            } catch (UnsupportedMessageException | UnknownIdentifierException e) {
                CompilerDirectives.transferToInterpreter();
                throw new RuntimeException("cannot read 'readable' member", e);
            }
        } else {
            if (memberInterop.hasArrayElements(delegate)) {
                return new ShreddedObjectMember(delegate, member);
            } else {
                CompilerDirectives.transferToInterpreter();
                throw new MapException("cannot shred object without size and member");
            }
        }
    }
}

@ExportLibrary(InteropLibrary.class)
final class ShreddedObjectMember implements TruffleObject {

    private final Object delegate;
    private final String member;

    ShreddedObjectMember(Object delegate, String member) {
        this.delegate = delegate;
        this.member = member;
    }

    @ExportMessage
    @SuppressWarnings("static-method")
    public boolean hasArrayElements() {
        return true;
    }

    @ExportMessage
    public long getArraySize(@CachedLibrary(limit = "2") InteropLibrary interop) {
        try {
            return interop.getArraySize(delegate);
        } catch (UnsupportedMessageException e) {
            CompilerDirectives.transferToInterpreter();
            throw new RuntimeException("cannot get size from 'hasArrayElements' object", e);
        }
    }

    @ExportMessage
    public boolean isArrayElementReadable(long index,
                    @CachedLibrary(limit = "2") InteropLibrary interop) {
        long size = getArraySize(interop);
        return index >= 0 && index < size;
    }

    @ExportMessage
    public Object readArrayElement(long index,
                    @CachedLibrary(limit = "2") InteropLibrary interop,
                    @CachedLibrary(limit = "2") InteropLibrary elementInterop) throws InvalidArrayIndexException {
        if (!isArrayElementReadable(index, interop)) {
            CompilerDirectives.transferToInterpreter();
            throw InvalidArrayIndexException.create(index);
        }
        Object element;
        try {
            element = interop.readArrayElement(delegate, index);
        } catch (UnsupportedMessageException e) {
            CompilerDirectives.transferToInterpreter();
            throw new MapException("cannot get element " + index + " from shredded object");
        }
        try {
            return elementInterop.readMember(element, member);
        } catch (UnsupportedMessageException | UnknownIdentifierException e) {
            CompilerDirectives.transferToInterpreter();
            throw new MapException("cannot get member '" + member + "' of element " + index + " from shredded object");
        }
    }
}