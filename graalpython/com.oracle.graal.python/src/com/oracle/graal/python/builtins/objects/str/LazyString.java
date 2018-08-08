/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.oracle.graal.python.builtins.objects.str;

import com.oracle.graal.python.nodes.PGuards;
import com.oracle.graal.python.runtime.PythonOptions;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.profiles.ConditionProfile;

public class LazyString implements CharSequence {
    
    protected static int MinLazyStringLength = PythonOptions.getMinLazyStringLength();
    protected static boolean UseLazyStrings = PythonOptions.useLazyString();
    
    public static int length(CharSequence cs, ConditionProfile profile1, ConditionProfile profile2) {
        if (profile1.profile(cs instanceof String)) {
            return ((String) cs).length();
        } else if (profile2.profile(cs instanceof LazyString)) {
            return ((LazyString) cs).length();
        }
        return lengthIntl(cs);
    }
    
    @TruffleBoundary
    private static int lengthIntl(CharSequence cs) {
        return cs.length();
    }
    
    @TruffleBoundary
    public static CharSequence create(CharSequence left, CharSequence right) {
        assert PGuards.isString(left);
        assert PGuards.isString(right);
        if (UseLazyStrings) {
            if (left.length() == 0) {
                return right;
            } else if (right.length() == 0) {
                return left;
            }
            int resultLength = left.length() + right.length();
            if (resultLength < MinLazyStringLength) {
                return left.toString() + right.toString();
            }
            return new LazyString(left, right, resultLength);
        } else {
            return left.toString() + right.toString();
        }
    }

    /**
     * Only use when invariants are checked already, e.g. from specializing nodes.
     */
    @TruffleBoundary
    public static CharSequence createChecked(CharSequence left, CharSequence right, int length) {
        assert assertChecked(left, right, length);
        return new LazyString(left, right, length);
    }

    private static boolean assertChecked(CharSequence left, CharSequence right, int length) {
        assert UseLazyStrings;
        assert (PGuards.isString(left) || left instanceof LazyString) && (PGuards.isString(right) || right instanceof LazyString);
        assert length == left.length() + right.length();
        assert left.length() > 0 && right.length() > 0;
        assert length >= MinLazyStringLength;
        return true;
    }

    /**
     * Variant of {@link #createChecked} that tries to concatenate a very short string to an already
     * short root leaf up-front, e.g. when appending single characters.
     */
    @TruffleBoundary
    public static CharSequence createCheckedShort(CharSequence left, CharSequence right, int length) {
        assertChecked(left, right, length);
        final int tinyLimit = 1;
        final int appendToLeafLimit = MinLazyStringLength / 2;
        if (left instanceof LazyString && right instanceof String && right.length() <= tinyLimit) {
            CharSequence ll = ((LazyString) left).left;
            CharSequence lr = ((LazyString) left).right;
            if (lr != null && lr instanceof String && lr.length() + right.length() <= appendToLeafLimit) {
                return new LazyString(ll, lr.toString() + right.toString(), length);
            }
        } else if (left instanceof String && left.length() <= tinyLimit && right instanceof LazyString) {
            CharSequence ll = ((LazyString) right).left;
            CharSequence lr = ((LazyString) right).right;
            if (lr != null && ll instanceof String && left.length() + ll.length() <= appendToLeafLimit) {
                return new LazyString(left.toString() + ll.toString(), lr, length);
            }
        }
        return new LazyString(left, right, length);
    }
    
    private CharSequence left;
    private CharSequence right;
    private final int length;
    
    private LazyString(CharSequence left, CharSequence right, int length) {
        assert left.length() > 0 && right.length() > 0 && length == left.length() + right.length();
        this.left = left;
        this.right = right;
        this.length = length;
    }

    private LazyString(CharSequence left, CharSequence right) {
        this(left, right, left.length() + right.length());
    }
    
    @Override
    public int length() {
        return length;
    }
    
    @Override
    public String toString() {
        if (!isFlat()) {
            flatten();
        }
        return (String) left;
    }

    private boolean isFlat() {
        return right == null;
    }

    @TruffleBoundary
    private void flatten() {
        char[] dst = new char[length];
        flatten(this, 0, length, dst, 0);
        left = new String(dst);
        right = null;
    }

    private static void flatten(CharSequence src, int srcBegin, int srcEnd, char[] dst, int dstBegin) {
        CompilerAsserts.neverPartOfCompilation();
        CharSequence str = src;
        int from = srcBegin;
        int to = srcEnd;
        int dstFrom = dstBegin;
        for (;;) {
            assert 0 <= from && from <= to && to <= str.length();
            if (str instanceof LazyString) {
                LazyString lazyString = (LazyString) str;
                CharSequence left = lazyString.left;
                CharSequence right = lazyString.right;
                int mid = left.length();

                if (to - mid >= mid - from) {
                    // right is longer, recurse left
                    if (from < mid) {
                        if (left instanceof String) {
                            ((String) left).getChars(from, mid, dst, dstFrom);
                        } else {
                            flatten(left, from, mid, dst, dstFrom);
                        }
                        dstFrom += mid - from;
                        from = 0;
                    } else {
                        from -= mid;
                    }
                    to -= mid;
                    str = right;
                } else {
                    // left is longer, recurse right
                    if (to > mid) {
                        if (right instanceof String) {
                            ((String) right).getChars(0, to - mid, dst, dstFrom + mid - from);
                        } else {
                            flatten(right, 0, to - mid, dst, dstFrom + mid - from);
                        }
                        to = mid;
                    }
                    str = left;
                }
            } else if (str instanceof String) {
                ((String) str).getChars(from, to, dst, dstFrom);
                return;
            }
        }
    }

    @Override
    public char charAt(int index) {
        return toString().charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return toString().subSequence(start, end);
    }

    public boolean isEmpty() {
        return length == 0;
    }

    // accessed via Java Interop, JDK-8062624.js
    @TruffleBoundary
    public boolean startsWith(String prefix) {
        return toString().startsWith(prefix);
    }

    // accessed via Java Interop, JDK-8062624.js
    @TruffleBoundary
    public boolean endsWith(String prefix) {
        return toString().endsWith(prefix);
    }

    // accessed via Java Interop, JDK-8062624.js
    @TruffleBoundary
    public byte[] getBytes() {
        return toString().getBytes();
    }    
}
