package dev.codex.pyscriptmod.script.lang;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Lexer {
    private static final Map<String, TokenType> KEYWORDS = new HashMap<>();

    static {
        KEYWORDS.put("def", TokenType.DEF);
        KEYWORDS.put("return", TokenType.RETURN);
        KEYWORDS.put("if", TokenType.IF);
        KEYWORDS.put("elif", TokenType.ELIF);
        KEYWORDS.put("else", TokenType.ELSE);
        KEYWORDS.put("while", TokenType.WHILE);
        KEYWORDS.put("for", TokenType.FOR);
        KEYWORDS.put("in", TokenType.IN);
        KEYWORDS.put("break", TokenType.BREAK);
        KEYWORDS.put("continue", TokenType.CONTINUE);
        KEYWORDS.put("import", TokenType.IMPORT);
        KEYWORDS.put("True", TokenType.TRUE);
        KEYWORDS.put("False", TokenType.FALSE);
        KEYWORDS.put("None", TokenType.NONE);
        KEYWORDS.put("and", TokenType.AND);
        KEYWORDS.put("or", TokenType.OR);
        KEYWORDS.put("not", TokenType.NOT);
    }

    public List<Token> lex(String source) {
        List<Token> tokens = new ArrayList<>();
        Deque<Integer> indents = new ArrayDeque<>();
        indents.push(0);

        String normalized = source.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        int lineNo = 1;

        for (String rawLine : lines) {
            int indent = countIndent(rawLine, lineNo);
            String line = rawLine.stripTrailing();
            String trimmed = line.stripLeading();
            boolean blank = trimmed.isEmpty() || trimmed.startsWith("#");

            if (!blank) {
                emitIndentation(tokens, indents, indent, lineNo);
                scanContent(tokens, trimmed, lineNo, indent + 1);
                tokens.add(new Token(TokenType.NEWLINE, "\\n", null, lineNo, line.length() + 1));
            }

            lineNo++;
        }

        if (!tokens.isEmpty() && tokens.get(tokens.size() - 1).type() != TokenType.NEWLINE) {
            tokens.add(new Token(TokenType.NEWLINE, "\\n", null, lineNo, 1));
        }

        while (indents.size() > 1) {
            indents.pop();
            tokens.add(new Token(TokenType.DEDENT, "", null, lineNo, 1));
        }

        tokens.add(new Token(TokenType.EOF, "", null, lineNo, 1));
        return tokens;
    }

    private int countIndent(String rawLine, int lineNo) {
        int spaces = 0;
        for (int i = 0; i < rawLine.length(); i++) {
            char c = rawLine.charAt(i);
            if (c == ' ') {
                spaces++;
            } else if (c == '\t') {
                spaces += 4;
            } else {
                break;
            }
        }

        if (spaces % 4 != 0) {
            throw new ScriptSyntaxException("Line " + lineNo + ": indentation must be a multiple of 4 spaces");
        }
        return spaces;
    }

    private void emitIndentation(List<Token> tokens, Deque<Integer> indents, int indent, int lineNo) {
        int current = indents.peek();
        if (indent > current) {
            indents.push(indent);
            tokens.add(new Token(TokenType.INDENT, "", null, lineNo, 1));
            return;
        }

        while (indent < current) {
            indents.pop();
            current = indents.peek();
            tokens.add(new Token(TokenType.DEDENT, "", null, lineNo, 1));
        }

        if (indent != current) {
            throw new ScriptSyntaxException("Line " + lineNo + ": inconsistent indentation");
        }
    }

    private void scanContent(List<Token> tokens, String line, int lineNo, int baseColumn) {
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            int column = baseColumn + i;
            switch (c) {
                case ' ', '\t' -> i++;
                case '#' -> {
                    return;
                }
                case '@' -> {
                    tokens.add(new Token(TokenType.AT, "@", null, lineNo, column));
                    i++;
                }
                case '(' -> {
                    tokens.add(new Token(TokenType.LPAREN, "(", null, lineNo, column));
                    i++;
                }
                case ')' -> {
                    tokens.add(new Token(TokenType.RPAREN, ")", null, lineNo, column));
                    i++;
                }
                case '[' -> {
                    tokens.add(new Token(TokenType.LBRACKET, "[", null, lineNo, column));
                    i++;
                }
                case ']' -> {
                    tokens.add(new Token(TokenType.RBRACKET, "]", null, lineNo, column));
                    i++;
                }
                case '{' -> {
                    tokens.add(new Token(TokenType.LBRACE, "{", null, lineNo, column));
                    i++;
                }
                case '}' -> {
                    tokens.add(new Token(TokenType.RBRACE, "}", null, lineNo, column));
                    i++;
                }
                case ',' -> {
                    tokens.add(new Token(TokenType.COMMA, ",", null, lineNo, column));
                    i++;
                }
                case ':' -> {
                    tokens.add(new Token(TokenType.COLON, ":", null, lineNo, column));
                    i++;
                }
                case '.' -> {
                    tokens.add(new Token(TokenType.DOT, ".", null, lineNo, column));
                    i++;
                }
                case '+' -> {
                    tokens.add(new Token(TokenType.PLUS, "+", null, lineNo, column));
                    i++;
                }
                case '-' -> {
                    tokens.add(new Token(TokenType.MINUS, "-", null, lineNo, column));
                    i++;
                }
                case '*' -> {
                    tokens.add(new Token(TokenType.STAR, "*", null, lineNo, column));
                    i++;
                }
                case '/' -> {
                    tokens.add(new Token(TokenType.SLASH, "/", null, lineNo, column));
                    i++;
                }
                case '%' -> {
                    tokens.add(new Token(TokenType.PERCENT, "%", null, lineNo, column));
                    i++;
                }
                case '=' -> {
                    if (match(line, i + 1, '=')) {
                        tokens.add(new Token(TokenType.EQEQ, "==", null, lineNo, column));
                        i += 2;
                    } else {
                        tokens.add(new Token(TokenType.EQ, "=", null, lineNo, column));
                        i++;
                    }
                }
                case '!' -> {
                    if (!match(line, i + 1, '=')) {
                        throw new ScriptSyntaxException("Line " + lineNo + ": unexpected '!'");
                    }
                    tokens.add(new Token(TokenType.NE, "!=", null, lineNo, column));
                    i += 2;
                }
                case '<' -> {
                    if (match(line, i + 1, '=')) {
                        tokens.add(new Token(TokenType.LE, "<=", null, lineNo, column));
                        i += 2;
                    } else {
                        tokens.add(new Token(TokenType.LT, "<", null, lineNo, column));
                        i++;
                    }
                }
                case '>' -> {
                    if (match(line, i + 1, '=')) {
                        tokens.add(new Token(TokenType.GE, ">=", null, lineNo, column));
                        i += 2;
                    } else {
                        tokens.add(new Token(TokenType.GT, ">", null, lineNo, column));
                        i++;
                    }
                }
                case '"', '\'' -> {
                    int start = i;
                    char quote = c;
                    i++;
                    StringBuilder builder = new StringBuilder();
                    while (i < line.length() && line.charAt(i) != quote) {
                        char current = line.charAt(i);
                        if (current == '\\' && i + 1 < line.length()) {
                            char next = line.charAt(i + 1);
                            builder.append(switch (next) {
                                case 'n' -> '\n';
                                case 't' -> '\t';
                                case '\\' -> '\\';
                                case '"' -> '"';
                                case '\'' -> '\'';
                                default -> next;
                            });
                            i += 2;
                        } else {
                            builder.append(current);
                            i++;
                        }
                    }
                    if (i >= line.length()) {
                        throw new ScriptSyntaxException("Line " + lineNo + ": unterminated string literal");
                    }
                    i++;
                    tokens.add(new Token(TokenType.STRING, line.substring(start, i), builder.toString(), lineNo, column));
                }
                default -> {
                    if (Character.isDigit(c)) {
                        int start = i;
                        boolean dotSeen = false;
                        while (i < line.length()) {
                            char current = line.charAt(i);
                            if (current == '.') {
                                if (dotSeen) {
                                    break;
                                }
                                dotSeen = true;
                                i++;
                            } else if (Character.isDigit(current)) {
                                i++;
                            } else {
                                break;
                            }
                        }
                        String number = line.substring(start, i);
                        Object literal = dotSeen ? Double.parseDouble(number) : Long.parseLong(number);
                        tokens.add(new Token(TokenType.NUMBER, number, literal, lineNo, column));
                    } else if (Character.isLetter(c) || c == '_') {
                        int start = i;
                        while (i < line.length()) {
                            char current = line.charAt(i);
                            if (Character.isLetterOrDigit(current) || current == '_') {
                                i++;
                            } else {
                                break;
                            }
                        }
                        String name = line.substring(start, i);
                        TokenType keyword = KEYWORDS.get(name);
                        tokens.add(new Token(keyword != null ? keyword : TokenType.IDENTIFIER, name, null, lineNo, column));
                    } else {
                        throw new ScriptSyntaxException("Line " + lineNo + ": unexpected character '" + c + "'");
                    }
                }
            }
        }
    }

    private boolean match(String line, int index, char expected) {
        return index < line.length() && line.charAt(index) == expected;
    }
}
