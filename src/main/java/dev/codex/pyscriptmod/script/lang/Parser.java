package dev.codex.pyscriptmod.script.lang;

import dev.codex.pyscriptmod.script.lang.Ast.Assign;
import dev.codex.pyscriptmod.script.lang.Ast.AttributeExpr;
import dev.codex.pyscriptmod.script.lang.Ast.BinaryExpr;
import dev.codex.pyscriptmod.script.lang.Ast.BreakStmt;
import dev.codex.pyscriptmod.script.lang.Ast.CallExpr;
import dev.codex.pyscriptmod.script.lang.Ast.ContinueStmt;
import dev.codex.pyscriptmod.script.lang.Ast.Decorator;
import dev.codex.pyscriptmod.script.lang.Ast.DictExpr;
import dev.codex.pyscriptmod.script.lang.Ast.Expr;
import dev.codex.pyscriptmod.script.lang.Ast.ExprStmt;
import dev.codex.pyscriptmod.script.lang.Ast.ForStmt;
import dev.codex.pyscriptmod.script.lang.Ast.FunctionDef;
import dev.codex.pyscriptmod.script.lang.Ast.IfStmt;
import dev.codex.pyscriptmod.script.lang.Ast.ImportStmt;
import dev.codex.pyscriptmod.script.lang.Ast.IndexExpr;
import dev.codex.pyscriptmod.script.lang.Ast.ListExpr;
import dev.codex.pyscriptmod.script.lang.Ast.LiteralExpr;
import dev.codex.pyscriptmod.script.lang.Ast.Module;
import dev.codex.pyscriptmod.script.lang.Ast.NameExpr;
import dev.codex.pyscriptmod.script.lang.Ast.ReturnStmt;
import dev.codex.pyscriptmod.script.lang.Ast.Stmt;
import dev.codex.pyscriptmod.script.lang.Ast.UnaryExpr;
import dev.codex.pyscriptmod.script.lang.Ast.WhileStmt;

import java.util.ArrayList;
import java.util.List;

public final class Parser {
    private final List<Token> tokens;
    private int current;

    public Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    public Module parse() {
        List<Stmt> statements = new ArrayList<>();
        skipNewlines();
        while (!isAtEnd()) {
            statements.add(statement(List.of()));
            skipNewlines();
        }
        return new Module(statements);
    }

    private Stmt statement(List<Decorator> pendingDecorators) {
        if (match(TokenType.AT)) {
            return statement(parseDecorators());
        }
        if (match(TokenType.IMPORT)) {
            String moduleName = consume(TokenType.IDENTIFIER, "expected module name after import").lexeme();
            return new ImportStmt(moduleName);
        }
        if (match(TokenType.DEF)) {
            return functionDef(pendingDecorators);
        }
        if (!pendingDecorators.isEmpty()) {
            throw error(previous(), "decorators can only be used before def");
        }
        if (match(TokenType.IF)) {
            return ifStmt();
        }
        if (match(TokenType.WHILE)) {
            return whileStmt();
        }
        if (match(TokenType.FOR)) {
            return forStmt();
        }
        if (match(TokenType.RETURN)) {
            Expr value = check(TokenType.NEWLINE) ? null : expression();
            return new ReturnStmt(value);
        }
        if (match(TokenType.BREAK)) {
            return new BreakStmt();
        }
        if (match(TokenType.CONTINUE)) {
            return new ContinueStmt();
        }
        return assignmentOrExpression();
    }

    private List<Decorator> parseDecorators() {
        List<Decorator> decorators = new ArrayList<>();
        do {
            Token name = consume(TokenType.IDENTIFIER, "expected decorator name");
            List<Expr> args = new ArrayList<>();
            if (match(TokenType.LPAREN)) {
                if (!check(TokenType.RPAREN)) {
                    do {
                        args.add(expression());
                    } while (match(TokenType.COMMA));
                }
                consume(TokenType.RPAREN, "expected ')' after decorator arguments");
            }
            consume(TokenType.NEWLINE, "expected newline after decorator");
            decorators.add(new Decorator(name.lexeme(), args));
        } while (match(TokenType.AT));
        return decorators;
    }

    private FunctionDef functionDef(List<Decorator> decorators) {
        Token name = consume(TokenType.IDENTIFIER, "expected function name");
        consume(TokenType.LPAREN, "expected '(' after function name");
        List<String> parameters = new ArrayList<>();
        if (!check(TokenType.RPAREN)) {
            do {
                parameters.add(consume(TokenType.IDENTIFIER, "expected parameter name").lexeme());
            } while (match(TokenType.COMMA));
        }
        consume(TokenType.RPAREN, "expected ')' after parameters");
        consume(TokenType.COLON, "expected ':' after function signature");
        List<Stmt> body = block();
        return new FunctionDef(name.lexeme(), parameters, decorators, body);
    }

    private IfStmt ifStmt() {
        List<IfStmt.Branch> branches = new ArrayList<>();
        Expr condition = expression();
        consume(TokenType.COLON, "expected ':' after if condition");
        branches.add(new IfStmt.Branch(condition, block()));
        while (match(TokenType.ELIF)) {
            Expr elifCondition = expression();
            consume(TokenType.COLON, "expected ':' after elif condition");
            branches.add(new IfStmt.Branch(elifCondition, block()));
        }
        List<Stmt> elseBranch = List.of();
        if (match(TokenType.ELSE)) {
            consume(TokenType.COLON, "expected ':' after else");
            elseBranch = block();
        }
        return new IfStmt(branches, elseBranch);
    }

    private WhileStmt whileStmt() {
        Expr condition = expression();
        consume(TokenType.COLON, "expected ':' after while condition");
        return new WhileStmt(condition, block());
    }

    private ForStmt forStmt() {
        String variable = consume(TokenType.IDENTIFIER, "expected loop variable").lexeme();
        consume(TokenType.IN, "expected 'in' after loop variable");
        Expr iterable = expression();
        consume(TokenType.COLON, "expected ':' after for iterable");
        return new ForStmt(variable, iterable, block());
    }

    private Stmt assignmentOrExpression() {
        if (check(TokenType.IDENTIFIER) && checkNext(TokenType.EQ)) {
            String name = advance().lexeme();
            advance();
            return new Assign(name, expression());
        }
        return new ExprStmt(expression());
    }

    private List<Stmt> block() {
        consume(TokenType.NEWLINE, "expected newline after ':'");
        consume(TokenType.INDENT, "expected indented block");
        List<Stmt> statements = new ArrayList<>();
        skipNewlines();
        while (!check(TokenType.DEDENT) && !isAtEnd()) {
            statements.add(statement(List.of()));
            skipNewlines();
        }
        consume(TokenType.DEDENT, "expected end of block");
        return statements;
    }

    private Expr expression() {
        return or();
    }

    private Expr or() {
        Expr expr = and();
        while (match(TokenType.OR)) {
            Token op = previous();
            expr = new BinaryExpr(expr, op.lexeme(), and());
        }
        return expr;
    }

    private Expr and() {
        Expr expr = equality();
        while (match(TokenType.AND)) {
            Token op = previous();
            expr = new BinaryExpr(expr, op.lexeme(), equality());
        }
        return expr;
    }

    private Expr equality() {
        Expr expr = comparison();
        while (match(TokenType.EQEQ, TokenType.NE)) {
            Token op = previous();
            expr = new BinaryExpr(expr, op.lexeme(), comparison());
        }
        return expr;
    }

    private Expr comparison() {
        Expr expr = term();
        while (match(TokenType.LT, TokenType.LE, TokenType.GT, TokenType.GE)) {
            Token op = previous();
            expr = new BinaryExpr(expr, op.lexeme(), term());
        }
        return expr;
    }

    private Expr term() {
        Expr expr = factor();
        while (match(TokenType.PLUS, TokenType.MINUS)) {
            Token op = previous();
            expr = new BinaryExpr(expr, op.lexeme(), factor());
        }
        return expr;
    }

    private Expr factor() {
        Expr expr = unary();
        while (match(TokenType.STAR, TokenType.SLASH, TokenType.PERCENT)) {
            Token op = previous();
            expr = new BinaryExpr(expr, op.lexeme(), unary());
        }
        return expr;
    }

    private Expr unary() {
        if (match(TokenType.NOT, TokenType.MINUS)) {
            Token op = previous();
            return new UnaryExpr(op.lexeme(), unary());
        }
        return postfix();
    }

    private Expr postfix() {
        Expr expr = primary();
        while (true) {
            if (match(TokenType.LPAREN)) {
                List<Expr> args = new ArrayList<>();
                if (!check(TokenType.RPAREN)) {
                    do {
                        args.add(expression());
                    } while (match(TokenType.COMMA));
                }
                consume(TokenType.RPAREN, "expected ')' after arguments");
                expr = new CallExpr(expr, args);
            } else if (match(TokenType.LBRACKET)) {
                Expr index = expression();
                consume(TokenType.RBRACKET, "expected ']' after index");
                expr = new IndexExpr(expr, index);
            } else if (match(TokenType.DOT)) {
                String attr = consume(TokenType.IDENTIFIER, "expected attribute name after '.'").lexeme();
                expr = new AttributeExpr(expr, attr);
            } else {
                return expr;
            }
        }
    }

    private Expr primary() {
        if (match(TokenType.NUMBER)) {
            return new LiteralExpr(previous().literal());
        }
        if (match(TokenType.STRING)) {
            return new LiteralExpr(previous().literal());
        }
        if (match(TokenType.TRUE)) {
            return new LiteralExpr(Boolean.TRUE);
        }
        if (match(TokenType.FALSE)) {
            return new LiteralExpr(Boolean.FALSE);
        }
        if (match(TokenType.NONE)) {
            return new LiteralExpr(null);
        }
        if (match(TokenType.IDENTIFIER)) {
            return new NameExpr(previous().lexeme());
        }
        if (match(TokenType.LPAREN)) {
            Expr expr = expression();
            consume(TokenType.RPAREN, "expected ')' after expression");
            return expr;
        }
        if (match(TokenType.LBRACKET)) {
            List<Expr> elements = new ArrayList<>();
            if (!check(TokenType.RBRACKET)) {
                do {
                    elements.add(expression());
                } while (match(TokenType.COMMA));
            }
            consume(TokenType.RBRACKET, "expected ']' after list literal");
            return new ListExpr(elements);
        }
        if (match(TokenType.LBRACE)) {
            List<DictExpr.Entry> entries = new ArrayList<>();
            if (!check(TokenType.RBRACE)) {
                do {
                    Expr key = expression();
                    consume(TokenType.COLON, "expected ':' between dict key and value");
                    Expr value = expression();
                    entries.add(new DictExpr.Entry(key, value));
                } while (match(TokenType.COMMA));
            }
            consume(TokenType.RBRACE, "expected '}' after dict literal");
            return new DictExpr(entries);
        }
        throw error(peek(), "expected expression");
    }

    private void skipNewlines() {
        while (match(TokenType.NEWLINE)) {
            // Skip blank separators.
        }
    }

    private boolean match(TokenType... types) {
        for (TokenType type : types) {
            if (check(type)) {
                advance();
                return true;
            }
        }
        return false;
    }

    private Token consume(TokenType type, String message) {
        if (check(type)) {
            return advance();
        }
        throw error(peek(), message);
    }

    private ScriptSyntaxException error(Token token, String message) {
        return new ScriptSyntaxException("Line " + token.line() + ", column " + token.column() + ": " + message);
    }

    private boolean check(TokenType type) {
        if (isAtEnd()) {
            return type == TokenType.EOF;
        }
        return peek().type() == type;
    }

    private boolean checkNext(TokenType type) {
        if (current + 1 >= tokens.size()) {
            return false;
        }
        return tokens.get(current + 1).type() == type;
    }

    private Token advance() {
        if (!isAtEnd()) {
            current++;
        }
        return previous();
    }

    private boolean isAtEnd() {
        return peek().type() == TokenType.EOF;
    }

    private Token peek() {
        return tokens.get(current);
    }

    private Token previous() {
        return tokens.get(current - 1);
    }
}
