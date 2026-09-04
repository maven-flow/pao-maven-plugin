package com.jardoapps.pao.pom;

/**
 * A single element occurrence in a scanned POM, remembering where its text
 * content lives in the original source so it can be rewritten in place.
 */
public final class XmlElement {

    private final String name;
    private final int index;
    private final int parentIndex;

    /** Bounds of the raw content between the start and end tag. */
    private int contentStart;
    private int contentEnd;

    /** Bounds of the content with surrounding whitespace trimmed off. */
    private int valueStart;
    private int valueEnd;

    XmlElement(String name, int index, int parentIndex, int contentStart) {
        this.name = name;
        this.index = index;
        this.parentIndex = parentIndex;
        this.contentStart = contentStart;
        this.contentEnd = contentStart;
        this.valueStart = contentStart;
        this.valueEnd = contentStart;
    }

    void closeAt(int contentEnd, String source) {
        this.contentEnd = contentEnd;
        int start = contentStart;
        int end = contentEnd;
        while (start < end && Character.isWhitespace(source.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(source.charAt(end - 1))) {
            end--;
        }
        this.valueStart = start;
        this.valueEnd = end;
    }

    public String getName() {
        return name;
    }

    public int getIndex() {
        return index;
    }

    public int getParentIndex() {
        return parentIndex;
    }

    public int getContentStart() {
        return contentStart;
    }

    public int getContentEnd() {
        return contentEnd;
    }

    public int getValueStart() {
        return valueStart;
    }

    public int getValueEnd() {
        return valueEnd;
    }

    @Override
    public String toString() {
        return "<" + name + "> @" + contentStart;
    }
}
