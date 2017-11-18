package com.google.googlejavaformat.java;

import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NonNLSHelper {
    private static final boolean DBG_NLS = false;

    private static final Pattern regexEndOfLine = Pattern.compile("\\R");
    private static final Pattern regexLiteral = Pattern.compile("[^\\'](\"([^\"\\\\]|\\\\.)*\")");
    private static final Pattern regexNonNLS = Pattern.compile("//\\$NON-NLS-([0-9]+)\\$");

    private enum CodeType { Code, SingleLineComment, MultipleLinesComment }
    private static class ParserState {
        CodeType type = CodeType.Code;
        int start = 0;
    }

    private static int validArgmin(int a, int b, int c) {
        if(a >= 0 && (b < 0 || a < b) && (c < 0 || a < c))
            return 0;
        if(b >= 0 && (a < 0 || b < a) && (c < 0 || b < c))
            return 1;
        if(c >= 0 && (a < 0 || c < a) && (b < 0 || c < b))
            return 2;
        return -1;
    }

    private static String[] getLines(final String content, final List<String> eol) throws FormatterException {
        Matcher meol = regexEndOfLine.matcher(content);
        while(meol.find())
            eol.add(meol.group());

        String[] lines = content.split("\\R", -1);
        if(eol.size() != lines.length-1) {
            throw new FormatterException("End of line detection failed");
        }
        return lines;
    }

    private static String findNextLiteral(String line, ParserState state) {
        if(state.start >= line.length()) {
            state.start = -1;
            return null;
        }
        switch (state.type) {
            case SingleLineComment:
                state.type = CodeType.Code;
                state.start = -1;
                return null;
            case MultipleLinesComment:
                int closingAt = line.indexOf("*/", state.start);
                if (closingAt >= 0) {
                    state.type = CodeType.Code;
                    state.start = closingAt + 2;
                } else
                    state.start = -1;
                return null;
            case Code:
                String restOfLine = line.substring(state.start);

                int singleLineComment = restOfLine.indexOf("//");
                int multipleLinesComment = restOfLine.indexOf("/*");
                Matcher ml = regexLiteral.matcher(restOfLine);
                int literal = ml.find() ? ml.start(1) : -1;
                int argmin = validArgmin(singleLineComment, multipleLinesComment, literal);
                switch (argmin) {
                    case 0:
                        state.type = CodeType.SingleLineComment;
                        state.start += singleLineComment + 2;
                        return null;
                    case 1:
                        state.type = CodeType.MultipleLinesComment;
                        state.start += multipleLinesComment + 2;
                        return null;
                    case 2:
                        state.start += ml.end(1);
                        return ml.group(1);
                    default:
                        state.start = -1;
                        return null;
                }
        }
        return null;
    }

    static boolean extractLiteralsAndNonNLS(final String content, final List<String> literals, final List<Boolean> nonNLS,
                                            RangeSet<Integer> rangeSet) throws FormatterException {
        boolean hasAnyNonNLS = false;

        List<String> eol = new LinkedList<>();
        String[] lines = getLines(content, eol);

        boolean testRangeBypass = rangeSet == null || rangeSet.encloses(Range.closedOpen(0, content.length()));

        int currentIndex = 0;
        ParserState pState = new ParserState();
        for (int l = 0 ; l < lines.length ; ++l) {
            List<String> lineLiterals = new LinkedList<>();
            pState.start = 0;
            while(pState.start != -1) {
                String literal = findNextLiteral(lines[l], pState);
                if (literal != null)
                    lineLiterals.add(literal);
            }

            boolean[] lineNonNLS = new boolean[lineLiterals.size()];
            Arrays.fill(lineNonNLS, false);
            if(testRangeBypass || rangeSet.encloses(Range.closedOpen(currentIndex, currentIndex+lines[l].length()))) {
                Matcher mnls = regexNonNLS.matcher(lines[l]);
                while (mnls.find()) {
                    int id = Integer.parseInt(mnls.group(1)) - 1;
                    if (id < lineNonNLS.length)
                        lineNonNLS[id] = true;
                }
            }

            for (int k = 0; k < lineLiterals.size(); ++k) {
                literals.add(lineLiterals.get(k));
                nonNLS.add(lineNonNLS[k]);
                hasAnyNonNLS = hasAnyNonNLS || lineNonNLS[k];
            }

            currentIndex += lines[l].length();
            if(l < lines.length-1) currentIndex += eol.get(l).length();
        }

        if(DBG_NLS) {
            System.out.println("" + literals.size() + " literals");
            for (int k = 0; k < literals.size(); ++k) {
                System.out.println("" + k + " -> " + literals.get(k) + "     " + nonNLS.get(k));
            }
        }

        return hasAnyNonNLS;
    }

    static String removeNonNLS(final String content, RangeSet<Integer> rangeSet) { // keeping same string length
        boolean testRangeBypass = rangeSet == null || rangeSet.encloses(Range.closedOpen(0, content.length()));
        StringBuilder builder = new StringBuilder(content);
        Matcher mnls = regexNonNLS.matcher(content);
        while (mnls.find()) {
            if(testRangeBypass || rangeSet.encloses(Range.closedOpen(mnls.start(), mnls.end()))) {
                for (int k = mnls.start(); k < mnls.end(); ++k)
                    builder.setCharAt(k, ' ');
            }
        }

        if(DBG_NLS) {
            System.out.println("Before formatting");
            System.out.println(builder.toString());
        }

        return builder.toString();
    }

    static String reinjectNonNLS(final String content, final List<String> literals, final List<Boolean> nonNLS) throws FormatterException {
        int idL = 0;
        int idLofLine = 0;

        List<String> eol = new LinkedList<>();
        String[] lines = getLines(content, eol);

        ParserState pState = new ParserState();
        StringJoiner joiner = new StringJoiner("");
        for (int k = 0 ; k < lines.length ; ++k) {
            idLofLine = 1;
            String suffixNonNLS = "";
            pState.start = 0;
            while(pState.start != -1) {
                String literal = findNextLiteral(lines[k], pState);
                if (literal != null) {
                    if (!literal.equals(literals.get(idL))) {
                        throw new FormatterException("Found literal " + literal + " does not match next expected literal " + literals.get(idL));
                    }
                    boolean hadNonNLS = nonNLS.get(idL);
                    if (hadNonNLS)
                        suffixNonNLS = suffixNonNLS + " //$NON-NLS-" + String.valueOf(idLofLine) + "$";
                    idL++;
                    idLofLine++;
                }
            }
            joiner.add(lines[k]);
            joiner.add(suffixNonNLS);
            if(k < lines.length-1) joiner.add(eol.get(k));
        }

        String result = joiner.toString();

        if(DBG_NLS) {
            System.out.println("After formatting");
            System.out.println(result);
        }

        return result;
    }
}
