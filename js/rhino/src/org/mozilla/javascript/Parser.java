/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * The contents of this file are subject to the Netscape Public
 * License Version 1.1 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.mozilla.org/NPL/
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * The Original Code is Rhino code, released
 * May 6, 1999.
 *
 * The Initial Developer of the Original Code is Netscape
 * Communications Corporation.  Portions created by Netscape are
 * Copyright (C) 1997-1999 Netscape Communications Corporation. All
 * Rights Reserved.
 *
 * Contributor(s):
 * Mike Ang
 * Mike McCabe
 *
 * Alternatively, the contents of this file may be used under the
 * terms of the GNU Public License (the "GPL"), in which case the
 * provisions of the GPL are applicable instead of those above.
 * If you wish to allow use of your version of this file only
 * under the terms of the GPL and not to allow others to use your
 * version of this file under the NPL, indicate your decision by
 * deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL.  If you do not delete
 * the provisions above, a recipient may use your version of this
 * file under either the NPL or the GPL.
 */

package org.mozilla.javascript;

import java.io.IOException;

/**
 * This class implements the JavaScript parser.
 *
 * It is based on the C source files jsparse.c and jsparse.h
 * in the jsref package.
 *
 * @see TokenStream
 *
 * @author Mike McCabe
 * @author Brendan Eich
 */

class Parser {

    public Parser() { }

    public void setLanguageVersion(int languageVersion) {
        this.languageVersion = languageVersion;
    }

    public void setAllowMemberExprAsFunctionName(boolean flag) {
        this.allowMemberExprAsFunctionName = flag;
    }

    private void mustMatchToken(TokenStream ts, int toMatch, String messageId)
        throws IOException, ParserException
    {
        int tt;
        if ((tt = ts.getToken()) != toMatch) {
            reportError(ts, messageId);
            ts.ungetToken(tt); // In case the parser decides to continue
        }
    }

    private void reportError(TokenStream ts, String messageId)
        throws ParserException
    {
        this.ok = false;
        ts.reportCurrentLineError(messageId, null);

        // Throw a ParserException exception to unwind the recursive descent
        // parse.
        throw new ParserException();
    }

    /*
     * Build a parse tree from the given TokenStream.
     *
     * @param ts the TokenStream to parse
     * @param nf the node factory to use to build parse nodes
     *
     * @return an Object representing the parsed
     * program.  If the parse fails, null will be returned.  (The
     * parse failure will result in a call to the current Context's
     * ErrorReporter.)
     */
    public ScriptOrFnNode parse(TokenStream ts, IRFactory nf)
        throws IOException
    {
        this.nf = nf;
        currentScriptOrFn = nf.createScript();

        this.ok = true;
        sourceTop = 0;

        int tt;          // last token from getToken();
        int baseLineno = ts.getLineno();  // line number where source starts

        /* so we have something to add nodes to until
         * we've collected all the source */
        Object pn = nf.createLeaf(Token.BLOCK);

        // Add script indicator
        sourceAdd((char)Token.SCRIPT);

        while (true) {
            ts.flags |= TokenStream.TSF_REGEXP;
            tt = ts.getToken();
            ts.flags &= ~TokenStream.TSF_REGEXP;

            if (tt <= Token.EOF) {
                break;
            }

            Object n;
            if (tt == Token.FUNCTION) {
                try {
                    n = function(ts, FunctionNode.FUNCTION_STATEMENT);
                } catch (ParserException e) {
                    this.ok = false;
                    break;
                }
            } else {
                ts.ungetToken(tt);
                n = statement(ts);
            }
            nf.addChildToBack(pn, n);
        }

        if (!this.ok) {
            // XXX ts.clearPushback() call here?
            return null;
        }

        String source = sourceToString(0);
        sourceBuffer = null; // To help GC

        nf.initScript(currentScriptOrFn, pn, ts.getSourceName(),
                      baseLineno, ts.getLineno(), source);
        return currentScriptOrFn;
    }

    /*
     * The C version of this function takes an argument list,
     * which doesn't seem to be needed for tree generation...
     * it'd only be useful for checking argument hiding, which
     * I'm not doing anyway...
     */
    private Object parseFunctionBody(TokenStream ts)
        throws IOException
    {
        int oldflags = ts.flags;
        ts.flags &= ~(TokenStream.TSF_RETURN_EXPR
                      | TokenStream.TSF_RETURN_VOID);
        ts.flags |= TokenStream.TSF_FUNCTION;

        Object pn = nf.createBlock(ts.getLineno());
        try {
            int tt;
            while((tt = ts.peekToken()) > Token.EOF && tt != Token.RC) {
                Object n;
                if (tt == Token.FUNCTION) {
                    ts.getToken();
                    n = function(ts, FunctionNode.FUNCTION_STATEMENT);
                } else {
                    n = statement(ts);
                }
                nf.addChildToBack(pn, n);
            }
        } catch (ParserException e) {
            this.ok = false;
        } finally {
            // also in finally block:
            // flushNewLines, clearPushback.

            ts.flags = oldflags;
        }

        return pn;
    }

    private Object function(TokenStream ts, int functionType)
        throws IOException, ParserException
    {
        int baseLineno = ts.getLineno();  // line number where source starts

        String name;
        Object memberExprNode = null;
        if (ts.matchToken(Token.NAME)) {
            name = ts.getString();
            if (!ts.matchToken(Token.LP)) {
                if (allowMemberExprAsFunctionName) {
                    // Extension to ECMA: if 'function <name>' does not follow
                    // by '(', assume <name> starts memberExpr
                    sourceAddString(Token.NAME, name);
                    Object memberExprHead = nf.createName(name);
                    name = "";
                    memberExprNode = memberExprTail(ts, false, memberExprHead);
                }
                mustMatchToken(ts, Token.LP, "msg.no.paren.parms");
            }
        } else if (ts.matchToken(Token.LP)) {
            // Anonymous function
            name = "";
        } else {
            name = "";
            if (allowMemberExprAsFunctionName) {
                // Note that memberExpr can not start with '(' like
                // in function (1+2).toString(), because 'function (' already
                // processed as anonymous function
                memberExprNode = memberExpr(ts, false);
            }
            mustMatchToken(ts, Token.LP, "msg.no.paren.parms");
        }

        if (memberExprNode != null) {
            // transform 'function' <memberExpr> to  <memberExpr> = function
            // even in the decompilated source
            sourceAdd((char)Token.ASSIGN);
            sourceAdd((char)Token.NOP);
        }

        FunctionNode fnNode = nf.createFunction(name);
        int functionIndex = currentScriptOrFn.addFunction(fnNode);

        // save a reference to the function in the enclosing source.
        sourceAdd((char) Token.FUNCTION);
        sourceAdd((char)functionIndex);

        // Save current source top to restore it on exit not to include
        // function to parent source
        int saved_sourceTop = sourceTop;

        ScriptOrFnNode savedScriptOrFn = currentScriptOrFn;
        currentScriptOrFn = fnNode;

        Object body;
        String source;
        try {
            // FUNCTION as the first token in a Source means it's a function
            // definition, and not a reference.
            sourceAdd((char) Token.FUNCTION);
            if (name.length() != 0) { sourceAddString(Token.NAME, name); }
            sourceAdd((char) Token.LP);

            if (!ts.matchToken(Token.RP)) {
                boolean first = true;
                do {
                    if (!first)
                        sourceAdd((char)Token.COMMA);
                    first = false;
                    mustMatchToken(ts, Token.NAME, "msg.no.parm");
                    String s = ts.getString();
                    if (fnNode.hasParamOrVar(s)) {
                        Object[] msgArgs = { s };
                        ts.reportCurrentLineWarning("msg.dup.parms", msgArgs);
                    }
                    fnNode.addParam(s);
                    sourceAddString(Token.NAME, s);
                } while (ts.matchToken(Token.COMMA));

                mustMatchToken(ts, Token.RP, "msg.no.paren.after.parms");
            }
            sourceAdd((char)Token.RP);

            mustMatchToken(ts, Token.LC, "msg.no.brace.body");
            sourceAdd((char)Token.LC);
            sourceAdd((char)Token.EOL);
            body = parseFunctionBody(ts);
            mustMatchToken(ts, Token.RC, "msg.no.brace.after.body");
            sourceAdd((char)Token.RC);
            // skip the last EOL so nested functions work...

            // name might be null;
            source = sourceToString(saved_sourceTop);
        }
        finally {
            sourceTop = saved_sourceTop;
            currentScriptOrFn = savedScriptOrFn;
        }

        Object pn;
        if (memberExprNode == null) {
            pn = nf.initFunction(fnNode, functionIndex, body,
                                 ts.getSourceName(),
                                 baseLineno, ts.getLineno(),
                                 source,
                                 functionType);
            if (functionType == FunctionNode.FUNCTION_EXPRESSION_STATEMENT) {
                // The following can be removed but then code generators should
                // be modified not to push on the stack function expression
                // statements
                pn = nf.createExprStatementNoReturn(pn, baseLineno);
            }
            // Add EOL but only if function is not part of expression, in which
            // case it gets SEMI + EOL from Statement.
            if (functionType != FunctionNode.FUNCTION_EXPRESSION) {
                sourceAdd((char)Token.EOL);
                checkWellTerminatedFunction(ts);
            }
        } else {
            pn = nf.initFunction(fnNode, functionIndex, body,
                                 ts.getSourceName(),
                                 baseLineno, ts.getLineno(),
                                 source,
                                 FunctionNode.FUNCTION_EXPRESSION);
            pn = nf.createBinary(Token.ASSIGN, Token.NOP, memberExprNode, pn);
            if (functionType != FunctionNode.FUNCTION_EXPRESSION) {
                pn = nf.createExprStatement(pn, baseLineno);
                // Add ';' to make 'function x.f(){}' and 'x.f = function(){}'
                // to print the same strings when decompiling
                sourceAdd((char)Token.SEMI);
                sourceAdd((char)Token.EOL);
                checkWellTerminatedFunction(ts);
            }
        }
        return pn;
    }

    private Object statements(TokenStream ts)
        throws IOException
    {
        Object pn = nf.createBlock(ts.getLineno());

        int tt;
        while((tt = ts.peekToken()) > Token.EOF && tt != Token.RC) {
            nf.addChildToBack(pn, statement(ts));
        }

        return pn;
    }

    private Object condition(TokenStream ts)
        throws IOException, ParserException
    {
        Object pn;
        mustMatchToken(ts, Token.LP, "msg.no.paren.cond");
        sourceAdd((char)Token.LP);
        pn = expr(ts, false);
        mustMatchToken(ts, Token.RP, "msg.no.paren.after.cond");
        sourceAdd((char)Token.RP);

        // there's a check here in jsparse.c that corrects = to ==

        return pn;
    }

    private void checkWellTerminated(TokenStream ts)
        throws IOException, ParserException
    {
        int tt = ts.peekTokenSameLine();
        switch (tt) {
        case Token.ERROR:
        case Token.EOF:
        case Token.EOL:
        case Token.SEMI:
        case Token.RC:
            return;

        case Token.FUNCTION:
            if (languageVersion < Context.VERSION_1_2) {
              /*
               * Checking against version < 1.2 and version >= 1.0
               * in the above line breaks old javascript, so we keep it
               * this way for now... XXX warning needed?
               */
                return;
            }
        }
        reportError(ts, "msg.no.semi.stmt");
    }

    private void checkWellTerminatedFunction(TokenStream ts)
        throws IOException, ParserException
    {
        if (languageVersion < Context.VERSION_1_2) {
            // See comments in checkWellTerminated
             return;
        }
        checkWellTerminated(ts);
    }

    // match a NAME; return null if no match.
    private String matchLabel(TokenStream ts)
        throws IOException, ParserException
    {
        int lineno = ts.getLineno();

        String label = null;
        int tt;
        tt = ts.peekTokenSameLine();
        if (tt == Token.NAME) {
            ts.getToken();
            label = ts.getString();
        }

        if (lineno == ts.getLineno())
            checkWellTerminated(ts);

        return label;
    }

    private Object statement(TokenStream ts)
        throws IOException
    {
        try {
            return statementHelper(ts);
        } catch (ParserException e) {
            // skip to end of statement
            int lineno = ts.getLineno();
            int t;
            do {
                t = ts.getToken();
            } while (t != Token.SEMI && t != Token.EOL &&
                     t != Token.EOF && t != Token.ERROR);
            return nf.createExprStatement(nf.createName("error"), lineno);
        }
    }

    /**
     * Whether the "catch (e: e instanceof Exception) { ... }" syntax
     * is implemented.
     */

    private Object statementHelper(TokenStream ts)
        throws IOException, ParserException
    {
        Object pn = null;

        // If skipsemi == true, don't add SEMI + EOL to source at the
        // end of this statment.  For compound statements, IF/FOR etc.
        boolean skipsemi = false;

        int tt;

        tt = ts.getToken();

        switch(tt) {
        case Token.IF: {
            skipsemi = true;

            sourceAdd((char)Token.IF);
            int lineno = ts.getLineno();
            Object cond = condition(ts);
            sourceAdd((char)Token.LC);
            sourceAdd((char)Token.EOL);
            Object ifTrue = statement(ts);
            Object ifFalse = null;
            if (ts.matchToken(Token.ELSE)) {
                sourceAdd((char)Token.RC);
                sourceAdd((char)Token.ELSE);
                sourceAdd((char)Token.LC);
                sourceAdd((char)Token.EOL);
                ifFalse = statement(ts);
            }
            sourceAdd((char)Token.RC);
            sourceAdd((char)Token.EOL);
            pn = nf.createIf(cond, ifTrue, ifFalse, lineno);
            break;
        }

        case Token.SWITCH: {
            skipsemi = true;

            sourceAdd((char)Token.SWITCH);
            pn = nf.createSwitch(ts.getLineno());

            Object cur_case = null;  // to kill warning
            Object case_statements;

            mustMatchToken(ts, Token.LP, "msg.no.paren.switch");
            sourceAdd((char)Token.LP);
            nf.addChildToBack(pn, expr(ts, false));
            mustMatchToken(ts, Token.RP, "msg.no.paren.after.switch");
            sourceAdd((char)Token.RP);
            mustMatchToken(ts, Token.LC, "msg.no.brace.switch");
            sourceAdd((char)Token.LC);
            sourceAdd((char)Token.EOL);

            while ((tt = ts.getToken()) != Token.RC && tt != Token.EOF) {
                switch(tt) {
                case Token.CASE:
                    sourceAdd((char)Token.CASE);
                    cur_case = nf.createUnary(Token.CASE, expr(ts, false));
                    sourceAdd((char)Token.COLON);
                    sourceAdd((char)Token.EOL);
                    break;

                case Token.DEFAULT:
                    cur_case = nf.createLeaf(Token.DEFAULT);
                    sourceAdd((char)Token.DEFAULT);
                    sourceAdd((char)Token.COLON);
                    sourceAdd((char)Token.EOL);
                    // XXX check that there isn't more than one default
                    break;

                default:
                    reportError(ts, "msg.bad.switch");
                    break;
                }
                mustMatchToken(ts, Token.COLON, "msg.no.colon.case");

                case_statements = nf.createLeaf(Token.BLOCK);

                while ((tt = ts.peekToken()) != Token.RC && tt != Token.CASE &&
                        tt != Token.DEFAULT && tt != Token.EOF)
                {
                    nf.addChildToBack(case_statements, statement(ts));
                }
                // assert cur_case
                nf.addChildToBack(cur_case, case_statements);

                nf.addChildToBack(pn, cur_case);
            }
            sourceAdd((char)Token.RC);
            sourceAdd((char)Token.EOL);
            break;
        }

        case Token.WHILE: {
            skipsemi = true;

            sourceAdd((char)Token.WHILE);
            int lineno = ts.getLineno();
            Object cond = condition(ts);
            sourceAdd((char)Token.LC);
            sourceAdd((char)Token.EOL);
            Object body = statement(ts);
            sourceAdd((char)Token.RC);
            sourceAdd((char)Token.EOL);

            pn = nf.createWhile(cond, body, lineno);
            break;

        }

        case Token.DO: {
            sourceAdd((char)Token.DO);
            sourceAdd((char)Token.LC);
            sourceAdd((char)Token.EOL);

            int lineno = ts.getLineno();

            Object body = statement(ts);

            sourceAdd((char)Token.RC);
            mustMatchToken(ts, Token.WHILE, "msg.no.while.do");
            sourceAdd((char)Token.WHILE);
            Object cond = condition(ts);

            pn = nf.createDoWhile(body, cond, lineno);
            break;
        }

        case Token.FOR: {
            skipsemi = true;

            sourceAdd((char)Token.FOR);
            int lineno = ts.getLineno();

            Object init;  // Node init is also foo in 'foo in Object'
            Object cond;  // Node cond is also object in 'foo in Object'
            Object incr = null; // to kill warning
            Object body;

            mustMatchToken(ts, Token.LP, "msg.no.paren.for");
            sourceAdd((char)Token.LP);
            tt = ts.peekToken();
            if (tt == Token.SEMI) {
                init = nf.createLeaf(Token.VOID);
            } else {
                if (tt == Token.VAR) {
                    // set init to a var list or initial
                    ts.getToken();    // throw away the 'var' token
                    init = variables(ts, true);
                }
                else {
                    init = expr(ts, true);
                }
            }

            tt = ts.peekToken();
            if (tt == Token.RELOP && ts.getOp() == Token.IN) {
                ts.matchToken(Token.RELOP);
                sourceAdd((char)Token.IN);
                // 'cond' is the object over which we're iterating
                cond = expr(ts, false);
            } else {  // ordinary for loop
                mustMatchToken(ts, Token.SEMI, "msg.no.semi.for");
                sourceAdd((char)Token.SEMI);
                if (ts.peekToken() == Token.SEMI) {
                    // no loop condition
                    cond = nf.createLeaf(Token.VOID);
                } else {
                    cond = expr(ts, false);
                }

                mustMatchToken(ts, Token.SEMI, "msg.no.semi.for.cond");
                sourceAdd((char)Token.SEMI);
                if (ts.peekToken() == Token.RP) {
                    incr = nf.createLeaf(Token.VOID);
                } else {
                    incr = expr(ts, false);
                }
            }

            mustMatchToken(ts, Token.RP, "msg.no.paren.for.ctrl");
            sourceAdd((char)Token.RP);
            sourceAdd((char)Token.LC);
            sourceAdd((char)Token.EOL);
            body = statement(ts);
            sourceAdd((char)Token.RC);
            sourceAdd((char)Token.EOL);

            if (incr == null) {
                // cond could be null if 'in obj' got eaten by the init node.
                pn = nf.createForIn(init, cond, body, lineno);
            } else {
                pn = nf.createFor(init, cond, incr, body, lineno);
            }
            break;
        }

        case Token.TRY: {
            int lineno = ts.getLineno();

            Object tryblock;
            Object catchblocks = null;
            Object finallyblock = null;

            skipsemi = true;
            sourceAdd((char)Token.TRY);
            sourceAdd((char)Token.LC);
            sourceAdd((char)Token.EOL);
            tryblock = statement(ts);
            sourceAdd((char)Token.RC);
            sourceAdd((char)Token.EOL);

            catchblocks = nf.createLeaf(Token.BLOCK);

            boolean sawDefaultCatch = false;
            int peek = ts.peekToken();
            if (peek == Token.CATCH) {
                while (ts.matchToken(Token.CATCH)) {
                    if (sawDefaultCatch) {
                        reportError(ts, "msg.catch.unreachable");
                    }
                    sourceAdd((char)Token.CATCH);
                    mustMatchToken(ts, Token.LP, "msg.no.paren.catch");
                    sourceAdd((char)Token.LP);

                    mustMatchToken(ts, Token.NAME, "msg.bad.catchcond");
                    String varName = ts.getString();
                    sourceAddString(Token.NAME, varName);

                    Object catchCond = null;
                    if (ts.matchToken(Token.IF)) {
                        sourceAdd((char)Token.IF);
                        catchCond = expr(ts, false);
                    } else {
                        sawDefaultCatch = true;
                    }

                    mustMatchToken(ts, Token.RP, "msg.bad.catchcond");
                    sourceAdd((char)Token.RP);
                    mustMatchToken(ts, Token.LC, "msg.no.brace.catchblock");
                    sourceAdd((char)Token.LC);
                    sourceAdd((char)Token.EOL);

                    nf.addChildToBack(catchblocks,
                        nf.createCatch(varName, catchCond,
                                       statements(ts),
                                       ts.getLineno()));

                    mustMatchToken(ts, Token.RC, "msg.no.brace.after.body");
                    sourceAdd((char)Token.RC);
                    sourceAdd((char)Token.EOL);
                }
            } else if (peek != Token.FINALLY) {
                mustMatchToken(ts, Token.FINALLY, "msg.try.no.catchfinally");
            }

            if (ts.matchToken(Token.FINALLY)) {
                sourceAdd((char)Token.FINALLY);

                sourceAdd((char)Token.LC);
                sourceAdd((char)Token.EOL);
                finallyblock = statement(ts);
                sourceAdd((char)Token.RC);
                sourceAdd((char)Token.EOL);
            }

            pn = nf.createTryCatchFinally(tryblock, catchblocks,
                                          finallyblock, lineno);

            break;
        }
        case Token.THROW: {
            int lineno = ts.getLineno();
            sourceAdd((char)Token.THROW);
            pn = nf.createThrow(expr(ts, false), lineno);
            if (lineno == ts.getLineno())
                checkWellTerminated(ts);
            break;
        }
        case Token.BREAK: {
            int lineno = ts.getLineno();

            sourceAdd((char)Token.BREAK);

            // matchLabel only matches if there is one
            String label = matchLabel(ts);
            if (label != null) {
                sourceAddString(Token.NAME, label);
            }
            pn = nf.createBreak(label, lineno);
            break;
        }
        case Token.CONTINUE: {
            int lineno = ts.getLineno();

            sourceAdd((char)Token.CONTINUE);

            // matchLabel only matches if there is one
            String label = matchLabel(ts);
            if (label != null) {
                sourceAddString(Token.NAME, label);
            }
            pn = nf.createContinue(label, lineno);
            break;
        }
        case Token.WITH: {
            skipsemi = true;

            sourceAdd((char)Token.WITH);
            int lineno = ts.getLineno();
            mustMatchToken(ts, Token.LP, "msg.no.paren.with");
            sourceAdd((char)Token.LP);
            Object obj = expr(ts, false);
            mustMatchToken(ts, Token.RP, "msg.no.paren.after.with");
            sourceAdd((char)Token.RP);
            sourceAdd((char)Token.LC);
            sourceAdd((char)Token.EOL);

            Object body = statement(ts);

            sourceAdd((char)Token.RC);
            sourceAdd((char)Token.EOL);

            pn = nf.createWith(obj, body, lineno);
            break;
        }
        case Token.VAR: {
            int lineno = ts.getLineno();
            pn = variables(ts, false);
            if (ts.getLineno() == lineno)
                checkWellTerminated(ts);
            break;
        }
        case Token.RETURN: {
            Object retExpr = null;

            sourceAdd((char)Token.RETURN);

            // bail if we're not in a (toplevel) function
            if ((ts.flags & ts.TSF_FUNCTION) == 0)
                reportError(ts, "msg.bad.return");

            /* This is ugly, but we don't want to require a semicolon. */
            ts.flags |= ts.TSF_REGEXP;
            tt = ts.peekTokenSameLine();
            ts.flags &= ~ts.TSF_REGEXP;

            int lineno = ts.getLineno();
            if (tt != Token.EOF && tt != Token.EOL && tt != Token.SEMI && tt != Token.RC) {
                retExpr = expr(ts, false);
                if (ts.getLineno() == lineno)
                    checkWellTerminated(ts);
                ts.flags |= ts.TSF_RETURN_EXPR;
            } else {
                ts.flags |= ts.TSF_RETURN_VOID;
            }

            // XXX ASSERT pn
            pn = nf.createReturn(retExpr, lineno);
            break;
        }
        case Token.LC:
            skipsemi = true;

            pn = statements(ts);
            mustMatchToken(ts, Token.RC, "msg.no.brace.block");
            break;

        case Token.ERROR:
            // Fall thru, to have a node for error recovery to work on
        case Token.EOL:
        case Token.SEMI:
            pn = nf.createLeaf(Token.VOID);
            skipsemi = true;
            break;

        case Token.FUNCTION: {
            pn = function(ts, FunctionNode.FUNCTION_EXPRESSION_STATEMENT);
            break;
        }

        default: {
                int lastExprType = tt;
                int tokenno = ts.getTokenno();
                ts.ungetToken(tt);
                int lineno = ts.getLineno();

                pn = expr(ts, false);

                if (ts.peekToken() == Token.COLON) {
                    /* check that the last thing the tokenizer returned was a
                     * NAME and that only one token was consumed.
                     */
                    if (lastExprType != Token.NAME || (ts.getTokenno() != tokenno))
                        reportError(ts, "msg.bad.label");

                    ts.getToken();  // eat the COLON

                    /* in the C source, the label is associated with the
                     * statement that follows:
                     *                nf.addChildToBack(pn, statement(ts));
                     */
                    String name = ts.getString();
                    pn = nf.createLabel(name, lineno);

                    // depend on decompiling lookahead to guess that that
                    // last name was a label.
                    sourceAdd((char)Token.COLON);
                    sourceAdd((char)Token.EOL);
                    return pn;
                }

                pn = nf.createExprStatement(pn, lineno);

                if (ts.getLineno() == lineno) {
                    checkWellTerminated(ts);
                }
                break;
            }
        }
        ts.matchToken(Token.SEMI);
        if (!skipsemi) {
            sourceAdd((char)Token.SEMI);
            sourceAdd((char)Token.EOL);
        }

        return pn;
    }

    private Object variables(TokenStream ts, boolean inForInit)
        throws IOException, ParserException
    {
        Object pn = nf.createVariables(ts.getLineno());
        boolean first = true;

        sourceAdd((char)Token.VAR);

        for (;;) {
            Object name;
            Object init;
            mustMatchToken(ts, Token.NAME, "msg.bad.var");
            String s = ts.getString();

            if (!first)
                sourceAdd((char)Token.COMMA);
            first = false;

            sourceAddString(Token.NAME, s);
            currentScriptOrFn.addVar(s);
            name = nf.createName(s);

            // omitted check for argument hiding

            if (ts.matchToken(Token.ASSIGN)) {
                if (ts.getOp() != Token.NOP)
                    reportError(ts, "msg.bad.var.init");

                sourceAdd((char)Token.ASSIGN);
                sourceAdd((char)Token.NOP);

                init = assignExpr(ts, inForInit);
                nf.addChildToBack(name, init);
            }
            nf.addChildToBack(pn, name);
            if (!ts.matchToken(Token.COMMA))
                break;
        }
        return pn;
    }

    private Object expr(TokenStream ts, boolean inForInit)
        throws IOException, ParserException
    {
        Object pn = assignExpr(ts, inForInit);
        while (ts.matchToken(Token.COMMA)) {
            sourceAdd((char)Token.COMMA);
            pn = nf.createBinary(Token.COMMA, pn, assignExpr(ts, inForInit));
        }
        return pn;
    }

    private Object assignExpr(TokenStream ts, boolean inForInit)
        throws IOException, ParserException
    {
        Object pn = condExpr(ts, inForInit);

        if (ts.matchToken(Token.ASSIGN)) {
            // omitted: "invalid assignment left-hand side" check.
            sourceAdd((char)Token.ASSIGN);
            sourceAdd((char)ts.getOp());
            pn = nf.createBinary(Token.ASSIGN, ts.getOp(), pn,
                                 assignExpr(ts, inForInit));
        }

        return pn;
    }

    private Object condExpr(TokenStream ts, boolean inForInit)
        throws IOException, ParserException
    {
        Object ifTrue;
        Object ifFalse;

        Object pn = orExpr(ts, inForInit);

        if (ts.matchToken(Token.HOOK)) {
            sourceAdd((char)Token.HOOK);
            ifTrue = assignExpr(ts, false);
            mustMatchToken(ts, Token.COLON, "msg.no.colon.cond");
            sourceAdd((char)Token.COLON);
            ifFalse = assignExpr(ts, inForInit);
            return nf.createTernary(pn, ifTrue, ifFalse);
        }

        return pn;
    }

    private Object orExpr(TokenStream ts, boolean inForInit)
        throws IOException, ParserException
    {
        Object pn = andExpr(ts, inForInit);
        if (ts.matchToken(Token.OR)) {
            sourceAdd((char)Token.OR);
            pn = nf.createBinary(Token.OR, pn, orExpr(ts, inForInit));
        }

        return pn;
    }

    private Object andExpr(TokenStream ts, boolean inForInit)
        throws IOException, ParserException
    {
        Object pn = bitOrExpr(ts, inForInit);
        if (ts.matchToken(Token.AND)) {
            sourceAdd((char)Token.AND);
            pn = nf.createBinary(Token.AND, pn, andExpr(ts, inForInit));
        }

        return pn;
    }

    private Object bitOrExpr(TokenStream ts, boolean inForInit)
        throws IOException, ParserException
    {
        Object pn = bitXorExpr(ts, inForInit);
        while (ts.matchToken(Token.BITOR)) {
            sourceAdd((char)Token.BITOR);
            pn = nf.createBinary(Token.BITOR, pn, bitXorExpr(ts, inForInit));
        }
        return pn;
    }

    private Object bitXorExpr(TokenStream ts, boolean inForInit)
        throws IOException, ParserException
    {
        Object pn = bitAndExpr(ts, inForInit);
        while (ts.matchToken(Token.BITXOR)) {
            sourceAdd((char)Token.BITXOR);
            pn = nf.createBinary(Token.BITXOR, pn, bitAndExpr(ts, inForInit));
        }
        return pn;
    }

    private Object bitAndExpr(TokenStream ts, boolean inForInit)
        throws IOException, ParserException
    {
        Object pn = eqExpr(ts, inForInit);
        while (ts.matchToken(Token.BITAND)) {
            sourceAdd((char)Token.BITAND);
            pn = nf.createBinary(Token.BITAND, pn, eqExpr(ts, inForInit));
        }
        return pn;
    }

    private Object eqExpr(TokenStream ts, boolean inForInit)
        throws IOException, ParserException
    {
        Object pn = relExpr(ts, inForInit);
        while (ts.matchToken(Token.EQOP)) {
            sourceAdd((char)Token.EQOP);
            sourceAdd((char)ts.getOp());
            pn = nf.createBinary(Token.EQOP, ts.getOp(), pn,
                                 relExpr(ts, inForInit));
        }
        return pn;
    }

    private Object relExpr(TokenStream ts, boolean inForInit)
        throws IOException, ParserException
    {
        Object pn = shiftExpr(ts);
        while (ts.matchToken(Token.RELOP)) {
            int op = ts.getOp();
            if (inForInit && op == Token.IN) {
                ts.ungetToken(Token.RELOP);
                break;
            }
            sourceAdd((char)Token.RELOP);
            sourceAdd((char)op);
            pn = nf.createBinary(Token.RELOP, op, pn, shiftExpr(ts));
        }
        return pn;
    }

    private Object shiftExpr(TokenStream ts)
        throws IOException, ParserException
    {
        Object pn = addExpr(ts);
        while (ts.matchToken(Token.SHOP)) {
            sourceAdd((char)Token.SHOP);
            sourceAdd((char)ts.getOp());
            pn = nf.createBinary(ts.getOp(), pn, addExpr(ts));
        }
        return pn;
    }

    private Object addExpr(TokenStream ts)
        throws IOException, ParserException
    {
        int tt;
        Object pn = mulExpr(ts);

        while ((tt = ts.getToken()) == Token.ADD || tt == Token.SUB) {
            sourceAdd((char)tt);
            // flushNewLines
            pn = nf.createBinary(tt, pn, mulExpr(ts));
        }
        ts.ungetToken(tt);

        return pn;
    }

    private Object mulExpr(TokenStream ts)
        throws IOException, ParserException
    {
        int tt;

        Object pn = unaryExpr(ts);

        while ((tt = ts.peekToken()) == Token.MUL ||
               tt == Token.DIV ||
               tt == Token.MOD) {
            tt = ts.getToken();
            sourceAdd((char)tt);
            pn = nf.createBinary(tt, pn, unaryExpr(ts));
        }


        return pn;
    }

    private Object unaryExpr(TokenStream ts)
        throws IOException, ParserException
    {
        int tt;

        ts.flags |= ts.TSF_REGEXP;
        tt = ts.getToken();
        ts.flags &= ~ts.TSF_REGEXP;

        switch(tt) {
        case Token.UNARYOP:
            sourceAdd((char)Token.UNARYOP);
            sourceAdd((char)ts.getOp());
            return nf.createUnary(Token.UNARYOP, ts.getOp(),
                                  unaryExpr(ts));

        case Token.ADD:
        case Token.SUB:
            sourceAdd((char)Token.UNARYOP);
            sourceAdd((char)tt);
            return nf.createUnary(Token.UNARYOP, tt, unaryExpr(ts));

        case Token.INC:
        case Token.DEC:
            sourceAdd((char)tt);
            return nf.createUnary(tt, Token.PRE, memberExpr(ts, true));

        case Token.DELPROP:
            sourceAdd((char)Token.DELPROP);
            return nf.createUnary(Token.DELPROP, unaryExpr(ts));

        case Token.ERROR:
            break;

        default:
            ts.ungetToken(tt);

            int lineno = ts.getLineno();

            Object pn = memberExpr(ts, true);

            /* don't look across a newline boundary for a postfix incop.

             * the rhino scanner seems to work differently than the js
             * scanner here; in js, it works to have the line number check
             * precede the peekToken calls.  It'd be better if they had
             * similar behavior...
             */
            int peeked;
            if (((peeked = ts.peekToken()) == Token.INC ||
                 peeked == Token.DEC) &&
                ts.getLineno() == lineno)
            {
                int pf = ts.getToken();
                sourceAdd((char)pf);
                return nf.createUnary(pf, Token.POST, pn);
            }
            return pn;
        }
        return nf.createName("err"); // Only reached on error.  Try to continue.

    }

    private Object argumentList(TokenStream ts, Object listNode)
        throws IOException, ParserException
    {
        boolean matched;
        ts.flags |= ts.TSF_REGEXP;
        matched = ts.matchToken(Token.RP);
        ts.flags &= ~ts.TSF_REGEXP;
        if (!matched) {
            boolean first = true;
            do {
                if (!first)
                    sourceAdd((char)Token.COMMA);
                first = false;
                nf.addChildToBack(listNode, assignExpr(ts, false));
            } while (ts.matchToken(Token.COMMA));

            mustMatchToken(ts, Token.RP, "msg.no.paren.arg");
        }
        sourceAdd((char)Token.RP);
        return listNode;
    }

    private Object memberExpr(TokenStream ts, boolean allowCallSyntax)
        throws IOException, ParserException
    {
        int tt;

        Object pn;

        /* Check for new expressions. */
        ts.flags |= ts.TSF_REGEXP;
        tt = ts.peekToken();
        ts.flags &= ~ts.TSF_REGEXP;
        if (tt == Token.NEW) {
            /* Eat the NEW token. */
            ts.getToken();
            sourceAdd((char)Token.NEW);

            /* Make a NEW node to append to. */
            pn = nf.createLeaf(Token.NEW);
            nf.addChildToBack(pn, memberExpr(ts, false));

            if (ts.matchToken(Token.LP)) {
                sourceAdd((char)Token.LP);
                /* Add the arguments to pn, if any are supplied. */
                pn = argumentList(ts, pn);
            }

            /* XXX there's a check in the C source against
             * "too many constructor arguments" - how many
             * do we claim to support?
             */

            /* Experimental syntax:  allow an object literal to follow a new expression,
             * which will mean a kind of anonymous class built with the JavaAdapter.
             * the object literal will be passed as an additional argument to the constructor.
             */
            tt = ts.peekToken();
            if (tt == Token.LC) {
                nf.addChildToBack(pn, primaryExpr(ts));
            }
        } else {
            pn = primaryExpr(ts);
        }

        return memberExprTail(ts, allowCallSyntax, pn);
    }

    private Object memberExprTail(TokenStream ts, boolean allowCallSyntax,
                                  Object pn)
        throws IOException, ParserException
    {
        int tt;
        while ((tt = ts.getToken()) > Token.EOF) {
            if (tt == Token.DOT) {
                sourceAdd((char)Token.DOT);
                mustMatchToken(ts, Token.NAME, "msg.no.name.after.dot");
                String s = ts.getString();
                sourceAddString(Token.NAME, s);
                pn = nf.createBinary(Token.DOT, pn,
                                     nf.createName(ts.getString()));
                /* pn = nf.createBinary(Token.DOT, pn, memberExpr(ts))
                 * is the version in Brendan's IR C version.  Not in ECMA...
                 * does it reflect the 'new' operator syntax he mentioned?
                 */
            } else if (tt == Token.LB) {
                sourceAdd((char)Token.LB);
                pn = nf.createBinary(Token.LB, pn, expr(ts, false));

                mustMatchToken(ts, Token.RB, "msg.no.bracket.index");
                sourceAdd((char)Token.RB);
            } else if (allowCallSyntax && tt == Token.LP) {
                /* make a call node */

                pn = nf.createUnary(Token.CALL, pn);
                sourceAdd((char)Token.LP);

                /* Add the arguments to pn, if any are supplied. */
                pn = argumentList(ts, pn);
            } else {
                ts.ungetToken(tt);

                break;
            }
        }
        return pn;
    }

    private Object primaryExpr(TokenStream ts)
        throws IOException, ParserException
    {
        int tt;

        Object pn;

        ts.flags |= ts.TSF_REGEXP;
        tt = ts.getToken();
        ts.flags &= ~ts.TSF_REGEXP;

        switch(tt) {

        case Token.FUNCTION:
            return function(ts, FunctionNode.FUNCTION_EXPRESSION);

        case Token.LB:
            {
                sourceAdd((char)Token.LB);
                pn = nf.createLeaf(Token.ARRAYLIT);

                ts.flags |= ts.TSF_REGEXP;
                boolean matched = ts.matchToken(Token.RB);
                ts.flags &= ~ts.TSF_REGEXP;

                if (!matched) {
                    boolean first = true;
                    do {
                        ts.flags |= ts.TSF_REGEXP;
                        tt = ts.peekToken();
                        ts.flags &= ~ts.TSF_REGEXP;

                        if (!first)
                            sourceAdd((char)Token.COMMA);
                        else
                            first = false;

                        if (tt == Token.RB) {  // to fix [,,,].length behavior...
                            break;
                        }

                        if (tt == Token.COMMA) {
                            nf.addChildToBack(pn, nf.createLeaf(Token.PRIMARY,
                                                                Token.UNDEFINED));
                        } else {
                            nf.addChildToBack(pn, assignExpr(ts, false));
                        }

                    } while (ts.matchToken(Token.COMMA));
                    mustMatchToken(ts, Token.RB, "msg.no.bracket.arg");
                }
                sourceAdd((char)Token.RB);
                return nf.createArrayLiteral(pn);
            }

        case Token.LC: {
            pn = nf.createLeaf(Token.OBJLIT);

            sourceAdd((char)Token.LC);
            if (!ts.matchToken(Token.RC)) {

                boolean first = true;
            commaloop:
                do {
                    Object property;

                    if (!first)
                        sourceAdd((char)Token.COMMA);
                    else
                        first = false;

                    tt = ts.getToken();
                    switch(tt) {
                        // map NAMEs to STRINGs in object literal context.
                    case Token.NAME:
                    case Token.STRING:
                        String s = ts.getString();
                        sourceAddString(Token.NAME, s);
                        property = nf.createString(ts.getString());
                        break;
                    case Token.NUMBER:
                        double n = ts.getNumber();
                        sourceAddNumber(n);
                        property = nf.createNumber(n);
                        break;
                    case Token.RC:
                        // trailing comma is OK.
                        ts.ungetToken(tt);
                        break commaloop;
                    default:
                        reportError(ts, "msg.bad.prop");
                        break commaloop;
                    }
                    mustMatchToken(ts, Token.COLON, "msg.no.colon.prop");

                    // OBJLIT is used as ':' in object literal for
                    // decompilation to solve spacing ambiguity.
                    sourceAdd((char)Token.OBJLIT);
                    nf.addChildToBack(pn, property);
                    nf.addChildToBack(pn, assignExpr(ts, false));

                } while (ts.matchToken(Token.COMMA));

                mustMatchToken(ts, Token.RC, "msg.no.brace.prop");
            }
            sourceAdd((char)Token.RC);
            return nf.createObjectLiteral(pn);
        }

        case Token.LP:

            /* Brendan's IR-jsparse.c makes a new node tagged with
             * TOK_LP here... I'm not sure I understand why.  Isn't
             * the grouping already implicit in the structure of the
             * parse tree?  also TOK_LP is already overloaded (I
             * think) in the C IR as 'function call.'  */
            sourceAdd((char)Token.LP);
            pn = expr(ts, false);
            sourceAdd((char)Token.RP);
            mustMatchToken(ts, Token.RP, "msg.no.paren");
            return pn;

        case Token.NAME:
            String name = ts.getString();
            sourceAddString(Token.NAME, name);
            return nf.createName(name);

        case Token.NUMBER:
            double n = ts.getNumber();
            sourceAddNumber(n);
            return nf.createNumber(n);

        case Token.STRING:
            String s = ts.getString();
            sourceAddString(Token.STRING, s);
            return nf.createString(s);

        case Token.REGEXP:
        {
            String flags = ts.regExpFlags;
            ts.regExpFlags = null;
            String re = ts.getString();
            sourceAddString(Token.REGEXP, '/' + re + '/' + flags);
            int index = currentScriptOrFn.addRegexp(re, flags);
            return nf.createRegExp(index);
        }

        case Token.PRIMARY:
            sourceAdd((char)Token.PRIMARY);
            sourceAdd((char)ts.getOp());
            return nf.createLeaf(Token.PRIMARY, ts.getOp());

        case Token.RESERVED:
            reportError(ts, "msg.reserved.id");
            break;

        case Token.ERROR:
            /* the scanner or one of its subroutines reported the error. */
            break;

        default:
            reportError(ts, "msg.syntax");
            break;

        }
        return null;    // should never reach here
    }

/**
 * The following methods save decompilation information about the source.
 * Source information is returned from the parser as a String
 * associated with function nodes and with the toplevel script.  When
 * saved in the constant pool of a class, this string will be UTF-8
 * encoded, and token values will occupy a single byte.

 * Source is saved (mostly) as token numbers.  The tokens saved pretty
 * much correspond to the token stream of a 'canonical' representation
 * of the input program, as directed by the parser.  (There were a few
 * cases where tokens could have been left out where decompiler could
 * easily reconstruct them, but I left them in for clarity).  (I also
 * looked adding source collection to TokenStream instead, where I
 * could have limited the changes to a few lines in getToken... but
 * this wouldn't have saved any space in the resulting source
 * representation, and would have meant that I'd have to duplicate
 * parser logic in the decompiler to disambiguate situations where
 * newlines are important.)  NativeFunction.decompile expands the
 * tokens back into their string representations, using simple
 * lookahead to correct spacing and indentation.

 * Token types with associated ops (ASSIGN, SHOP, PRIMARY, etc.) are
 * saved as two-token pairs.  Number tokens are stored inline, as a
 * NUMBER token, a character representing the type, and either 1 or 4
 * characters representing the bit-encoding of the number.  String
 * types NAME, STRING and OBJECT are currently stored as a token type,
 * followed by a character giving the length of the string (assumed to
 * be less than 2^16), followed by the characters of the string
 * inlined into the source string.  Changing this to some reference to
 * to the string in the compiled class' constant pool would probably
 * save a lot of space... but would require some method of deriving
 * the final constant pool entry from information available at parse
 * time.

 * Nested functions need a similar mechanism... fortunately the nested
 * functions for a given function are generated in source order.
 * Nested functions are encoded as FUNCTION followed by a function
 * number (encoded as a character), which is enough information to
 * find the proper generated NativeFunction instance.

 */
    private void sourceAdd(char c) {
        if (sourceTop == sourceBuffer.length) {
            increaseSourceCapacity(sourceTop + 1);
        }
        sourceBuffer[sourceTop] = c;
        ++sourceTop;
    }

    private void sourceAddString(int type, String str) {
        sourceAdd((char)type);
        sourceAddString(str);
    }

    private void sourceAddString(String str) {
        int L = str.length();
        int lengthEncodingSize = 1;
        if (L >= 0x8000) {
            lengthEncodingSize = 2;
        }
        int nextTop = sourceTop + lengthEncodingSize + L;
        if (nextTop > sourceBuffer.length) {
            increaseSourceCapacity(nextTop);
        }
        if (L >= 0x8000) {
            // Use 2 chars to encode strings exceeding 32K, were the highest
            // bit in the first char indicates presence of the next byte
            sourceBuffer[sourceTop] = (char)(0x8000 | (L >>> 16));
            ++sourceTop;
        }
        sourceBuffer[sourceTop] = (char)L;
        ++sourceTop;
        str.getChars(0, L, sourceBuffer, sourceTop);
        sourceTop = nextTop;
    }

    private void sourceAddNumber(double n) {
        sourceAdd((char)Token.NUMBER);

        /* encode the number in the source stream.
         * Save as NUMBER type (char | char char char char)
         * where type is
         * 'D' - double, 'S' - short, 'J' - long.

         * We need to retain float vs. integer type info to keep the
         * behavior of liveconnect type-guessing the same after
         * decompilation.  (Liveconnect tries to present 1.0 to Java
         * as a float/double)
         * OPT: This is no longer true. We could compress the format.

         * This may not be the most space-efficient encoding;
         * the chars created below may take up to 3 bytes in
         * constant pool UTF-8 encoding, so a Double could take
         * up to 12 bytes.
         */

        long lbits = (long)n;
        if (lbits != n) {
            // if it's floating point, save as a Double bit pattern.
            // (12/15/97 our scanner only returns Double for f.p.)
            lbits = Double.doubleToLongBits(n);
            sourceAdd('D');
            sourceAdd((char)(lbits >> 48));
            sourceAdd((char)(lbits >> 32));
            sourceAdd((char)(lbits >> 16));
            sourceAdd((char)lbits);
        }
        else {
            // we can ignore negative values, bc they're already prefixed
            // by UNARYOP SUB
               if (lbits < 0) Context.codeBug();

            // will it fit in a char?
            // this gives a short encoding for integer values up to 2^16.
            if (lbits <= Character.MAX_VALUE) {
                sourceAdd('S');
                sourceAdd((char)lbits);
            }
            else { // Integral, but won't fit in a char. Store as a long.
                sourceAdd('J');
                sourceAdd((char)(lbits >> 48));
                sourceAdd((char)(lbits >> 32));
                sourceAdd((char)(lbits >> 16));
                sourceAdd((char)lbits);
            }
        }
    }

    private void increaseSourceCapacity(int minimalCapacity) {
        // Call this only when capacity increase is must
        if (minimalCapacity <= sourceBuffer.length) Context.codeBug();
        int newCapacity = sourceBuffer.length * 2;
        if (newCapacity < minimalCapacity) {
            newCapacity = minimalCapacity;
        }
        char[] tmp = new char[newCapacity];
        System.arraycopy(sourceBuffer, 0, tmp, 0, sourceTop);
        sourceBuffer = tmp;
    }

    private String sourceToString(int offset) {
        if (offset < 0 || sourceTop < offset) Context.codeBug();
        return new String(sourceBuffer, offset, sourceTop - offset);
    }

    /**
     * Decompile the source information associated with this js
     * function/script back into a string.  For the most part, this
     * just means translating tokens back to their string
     * representations; there's a little bit of lookahead logic to
     * decide the proper spacing/indentation.  Most of the work in
     * mapping the original source to the prettyprinted decompiled
     * version is done by the parser.
     *
     * Note that support for Context.decompileFunctionBody is hacked
     * on through special cases; I suspect that js makes a distinction
     * between function header and function body that rhino
     * decompilation does not.
     *
     * @param encodedSourcesTree See {@link NativeFunction#getSourcesTree()}
     *        for definition
     *
     * @param fromFunctionConstructor true if encodedSourcesTree represents
     *                                result of Function(...)
     *
     * @param version engine version used to compile the source
     *
     * @param indent How much to indent the decompiled result
     *
     * @param justbody Whether the decompilation should omit the
     * function header and trailing brace.
     */
    static String decompile(Object encodedSourcesTree,
                            boolean fromFunctionConstructor, int version,
                            int indent, boolean justbody)
    {
        StringBuffer result = new StringBuffer();
        Object[] srcData = new Object[1];
        int type = fromFunctionConstructor ? CONSTRUCTED_FUNCTION
                                           : TOP_LEVEL_SCRIPT_OR_FUNCTION;

        decompile_r(encodedSourcesTree, version, indent, type, justbody,
                    srcData, result);
        return result.toString();
    }

    private static void decompile_r(Object encodedSourcesTree, int version,
                                    int indent, int type, boolean justbody,
                                    Object[] srcData, StringBuffer result)
    {
        final int OFFSET = 4; // how much to indent
        final int SETBACK = 2; // less how much for case labels

        String source;
        Object[] childNodes = null;
        if (encodedSourcesTree == null) {
            source = null;
        } else if (encodedSourcesTree instanceof String) {
            source = (String)encodedSourcesTree;
        } else {
            childNodes = (Object[])encodedSourcesTree;
            source = (String)childNodes[0];
        }

        if (source == null) { return; }

        int length = source.length();
        if (length == 0) { return; }

        // Spew tokens in source, for debugging.
        // as TYPE number char
        if (printSource) {
            System.err.println("length:" + length);
            for (int i = 0; i < length; ++i) {
                // Note that tokenToName will fail unless Context.printTrees
                // is true.
                String tokenname = null;
                if (Token.printTokenNames) {
                    tokenname = Token.name(source.charAt(i));
                }
                if (tokenname == null) {
                    tokenname = "---";
                }
                String pad = tokenname.length() > 7
                    ? "\t"
                    : "\t\t";
                System.err.println
                    (tokenname
                     + pad + (int)source.charAt(i)
                     + "\t'" + ScriptRuntime.escapeString
                     (source.substring(i, i+1))
                     + "'");
            }
            System.err.println();
        }

        if (type != NESTED_FUNCTION) {
            // add an initial newline to exactly match js.
            if (!justbody)
                result.append('\n');
            for (int j = 0; j < indent; j++)
                result.append(' ');
        }

        int i = 0;

        // If the first token is Token.SCRIPT, then we're
        // decompiling the toplevel script, otherwise it a function
        // and should start with Token.FUNCTION

        int token = source.charAt(i);
        ++i;
        if (token == Token.FUNCTION) {
            if (!justbody) {
                result.append("function ");

                /* version != 1.2 Function constructor behavior -
                 * print 'anonymous' as the function name if the
                 * version (under which the function was compiled) is
                 * less than 1.2... or if it's greater than 1.2, because
                 * we need to be closer to ECMA.  (ToSource, please?)
                 */
                if (source.charAt(i) == Token.LP
                    && version != Context.VERSION_1_2
                    && type == CONSTRUCTED_FUNCTION)
                {
                    result.append("anonymous");
                }
            } else {
                // Skip past the entire function header pass the next EOL.
                skipLoop: for (;;) {
                    token = source.charAt(i);
                    ++i;
                    switch (token) {
                        case Token.EOL:
                            break skipLoop;
                        case Token.NAME:
                            // Skip function or argument name
                            i = Parser.getSourceString(source, i, null);
                            break;
                        case Token.LP:
                        case Token.COMMA:
                        case Token.RP:
                            break;
                        default:
                            // Bad function header
                            throw new RuntimeException();
                    }
                }
            }
        } else if (token != Token.SCRIPT) {
            // Bad source header
            throw new RuntimeException();
        }

        while (i < length) {
            switch(source.charAt(i)) {
            case Token.NAME:
            case Token.REGEXP:  // re-wrapped in '/'s in parser...
                /* NAMEs are encoded as NAME, (char) length, string...
                 * Note that lookahead for detecting labels depends on
                 * this encoding; change there if this changes.

                 * Also change function-header skipping code above,
                 * used when decompling under decompileFunctionBody.
                 */
                i = Parser.getSourceString(source, i + 1, srcData);
                result.append((String)srcData[0]);
                continue;

            case Token.NUMBER: {
                i = Parser.getSourceNumber(source, i + 1, srcData);
                double number = ((Number)srcData[0]).doubleValue();
                result.append(ScriptRuntime.numberToString(number, 10));
                continue;
            }

            case Token.STRING:
                i = Parser.getSourceString(source, i + 1, srcData);
                result.append('"');
                result.append(ScriptRuntime.escapeString((String)srcData[0]));
                result.append('"');
                continue;

            case Token.PRIMARY:
                ++i;
                switch(source.charAt(i)) {
                case Token.TRUE:
                    result.append("true");
                    break;

                case Token.FALSE:
                    result.append("false");
                    break;

                case Token.NULL:
                    result.append("null");
                    break;

                case Token.THIS:
                    result.append("this");
                    break;

                case Token.TYPEOF:
                    result.append("typeof");
                    break;

                case Token.VOID:
                    result.append("void");
                    break;

                case Token.UNDEFINED:
                    result.append("undefined");
                    break;
                }
                break;

            case Token.FUNCTION: {
                /* decompile a FUNCTION token as an escape; call
                 * toString on the nth enclosed nested function,
                 * where n is given by the byte that follows.
                 */

                ++i;
                int functionIndex = source.charAt(i);
                if (childNodes == null
                    || functionIndex + 1 > childNodes.length)
                {
                    throw Context.reportRuntimeError(Context.getMessage1
                        ("msg.no.function.ref.found",
                         new Integer(functionIndex)));
                }
                decompile_r(childNodes[functionIndex + 1], version,
                            indent, NESTED_FUNCTION, false, srcData, result);
                break;
            }
            case Token.COMMA:
                result.append(", ");
                break;

            case Token.LC:
                if (nextIs(source, length, i, Token.EOL))
                    indent += OFFSET;
                result.append('{');
                break;

            case Token.RC:
                /* don't print the closing RC if it closes the
                 * toplevel function and we're called from
                 * decompileFunctionBody.
                 */
                if (justbody && type != NESTED_FUNCTION && i + 1 == length)
                    break;

                if (nextIs(source, length, i, Token.EOL))
                    indent -= OFFSET;
                if (nextIs(source, length, i, Token.WHILE)
                    || nextIs(source, length, i, Token.ELSE)) {
                    indent -= OFFSET;
                    result.append("} ");
                }
                else
                    result.append('}');
                break;

            case Token.LP:
                result.append('(');
                break;

            case Token.RP:
                if (nextIs(source, length, i, Token.LC))
                    result.append(") ");
                else
                    result.append(')');
                break;

            case Token.LB:
                result.append('[');
                break;

            case Token.RB:
                result.append(']');
                break;

            case Token.EOL:
                result.append('\n');

                /* add indent if any tokens remain,
                 * less setback if next token is
                 * a label, case or default.
                 */
                if (i + 1 < length) {
                    int less = 0;
                    int nextToken = source.charAt(i + 1);
                    if (nextToken == Token.CASE
                        || nextToken == Token.DEFAULT)
                        less = SETBACK;
                    else if (nextToken == Token.RC)
                        less = OFFSET;

                    /* elaborate check against label... skip past a
                     * following inlined NAME and look for a COLON.
                     * Depends on how NAME is encoded.
                     */
                    else if (nextToken == Token.NAME) {
                        int afterName = Parser.getSourceString(source, i + 2,
                                                               null);
                        if (source.charAt(afterName) == Token.COLON)
                            less = OFFSET;
                    }

                    for (; less < indent; less++)
                        result.append(' ');
                }
                break;

            case Token.DOT:
                result.append('.');
                break;

            case Token.NEW:
                result.append("new ");
                break;

            case Token.DELPROP:
                result.append("delete ");
                break;

            case Token.IF:
                result.append("if ");
                break;

            case Token.ELSE:
                result.append("else ");
                break;

            case Token.FOR:
                result.append("for ");
                break;

            case Token.IN:
                result.append(" in ");
                break;

            case Token.WITH:
                result.append("with ");
                break;

            case Token.WHILE:
                result.append("while ");
                break;

            case Token.DO:
                result.append("do ");
                break;

            case Token.TRY:
                result.append("try ");
                break;

            case Token.CATCH:
                result.append("catch ");
                break;

            case Token.FINALLY:
                result.append("finally ");
                break;

            case Token.THROW:
                result.append("throw ");
                break;

            case Token.SWITCH:
                result.append("switch ");
                break;

            case Token.BREAK:
                if (nextIs(source, length, i, Token.NAME))
                    result.append("break ");
                else
                    result.append("break");
                break;

            case Token.CONTINUE:
                if (nextIs(source, length, i, Token.NAME))
                    result.append("continue ");
                else
                    result.append("continue");
                break;

            case Token.CASE:
                result.append("case ");
                break;

            case Token.DEFAULT:
                result.append("default");
                break;

            case Token.RETURN:
                if (nextIs(source, length, i, Token.SEMI))
                    result.append("return");
                else
                    result.append("return ");
                break;

            case Token.VAR:
                result.append("var ");
                break;

            case Token.SEMI:
                if (nextIs(source, length, i, Token.EOL))
                    // statement termination
                    result.append(';');
                else
                    // separators in FOR
                    result.append("; ");
                break;

            case Token.ASSIGN:
                ++i;
                switch(source.charAt(i)) {
                case Token.NOP:
                    result.append(" = ");
                    break;

                case Token.ADD:
                    result.append(" += ");
                    break;

                case Token.SUB:
                    result.append(" -= ");
                    break;

                case Token.MUL:
                    result.append(" *= ");
                    break;

                case Token.DIV:
                    result.append(" /= ");
                    break;

                case Token.MOD:
                    result.append(" %= ");
                    break;

                case Token.BITOR:
                    result.append(" |= ");
                    break;

                case Token.BITXOR:
                    result.append(" ^= ");
                    break;

                case Token.BITAND:
                    result.append(" &= ");
                    break;

                case Token.LSH:
                    result.append(" <<= ");
                    break;

                case Token.RSH:
                    result.append(" >>= ");
                    break;

                case Token.URSH:
                    result.append(" >>>= ");
                    break;
                }
                break;

            case Token.HOOK:
                result.append(" ? ");
                break;

            case Token.OBJLIT:
                // pun OBJLIT to mean colon in objlit property initialization.
                // this needs to be distinct from COLON in the general case
                // to distinguish from the colon in a ternary... which needs
                // different spacing.
                result.append(':');
                break;

            case Token.COLON:
                if (nextIs(source, length, i, Token.EOL))
                    // it's the end of a label
                    result.append(':');
                else
                    // it's the middle part of a ternary
                    result.append(" : ");
                break;

            case Token.OR:
                result.append(" || ");
                break;

            case Token.AND:
                result.append(" && ");
                break;

            case Token.BITOR:
                result.append(" | ");
                break;

            case Token.BITXOR:
                result.append(" ^ ");
                break;

            case Token.BITAND:
                result.append(" & ");
                break;

            case Token.EQOP:
                ++i;
                switch(source.charAt(i)) {
                case Token.SHEQ:
                    /*
                     * Emulate the C engine; if we're under version
                     * 1.2, then the == operator behaves like the ===
                     * operator (and the source is generated by
                     * decompiling a === opcode), so print the ===
                     * operator as ==.
                     */
                    result.append(version == Context.VERSION_1_2
                                  ? " == " : " === ");
                    break;

                case Token.SHNE:
                    result.append(version == Context.VERSION_1_2
                                  ? " != " : " !== ");
                    break;

                case Token.EQ:
                    result.append(" == ");
                    break;

                case Token.NE:
                    result.append(" != ");
                    break;
                }
                break;

            case Token.RELOP:
                ++i;
                switch(source.charAt(i)) {
                case Token.LE:
                    result.append(" <= ");
                    break;

                case Token.LT:
                    result.append(" < ");
                    break;

                case Token.GE:
                    result.append(" >= ");
                    break;

                case Token.GT:
                    result.append(" > ");
                    break;

                case Token.INSTANCEOF:
                    result.append(" instanceof ");
                    break;
                }
                break;

            case Token.SHOP:
                ++i;
                switch(source.charAt(i)) {
                case Token.LSH:
                    result.append(" << ");
                    break;

                case Token.RSH:
                    result.append(" >> ");
                    break;

                case Token.URSH:
                    result.append(" >>> ");
                    break;
                }
                break;

            case Token.UNARYOP:
                ++i;
                switch(source.charAt(i)) {
                case Token.TYPEOF:
                    result.append("typeof ");
                    break;

                case Token.VOID:
                    result.append("void ");
                    break;

                case Token.NOT:
                    result.append('!');
                    break;

                case Token.BITNOT:
                    result.append('~');
                    break;

                case Token.ADD:
                    result.append('+');
                    break;

                case Token.SUB:
                    result.append('-');
                    break;
                }
                break;

            case Token.INC:
                result.append("++");
                break;

            case Token.DEC:
                result.append("--");
                break;

            case Token.ADD:
                result.append(" + ");
                break;

            case Token.SUB:
                result.append(" - ");
                break;

            case Token.MUL:
                result.append(" * ");
                break;

            case Token.DIV:
                result.append(" / ");
                break;

            case Token.MOD:
                result.append(" % ");
                break;

            default:
                // If we don't know how to decompile it, raise an exception.
                throw new RuntimeException();
            }
            ++i;
        }

        // add that trailing newline if it's an outermost function.
        if (type != NESTED_FUNCTION && !justbody)
            result.append('\n');
    }

    private static boolean nextIs(String source, int length, int i, int token) {
        return (i + 1 < length) ? source.charAt(i + 1) == token : false;
    }

    private static int getSourceString(String source, int offset,
                                       Object[] result)
    {
        int length = source.charAt(offset);
        ++offset;
        if ((0x8000 & length) != 0) {
            length = ((0x7FFF & length) << 16) | source.charAt(offset);
            ++offset;
        }
        if (result != null) {
            result[0] = source.substring(offset, offset + length);
        }
        return offset + length;
    }

    private static int getSourceNumber(String source, int offset,
                                       Object[] result)
    {
        char type = source.charAt(offset);
        ++offset;
        if (type == 'S') {
            if (result != null) {
                int ival = source.charAt(offset);
                result[0] = new Integer(ival);
            }
            ++offset;
        } else if (type == 'J' || type == 'D') {
            if (result != null) {
                long lbits;
                lbits = (long)source.charAt(offset) << 48;
                lbits |= (long)source.charAt(offset + 1) << 32;
                lbits |= (long)source.charAt(offset + 2) << 16;
                lbits |= (long)source.charAt(offset + 3);
                double dval;
                if (type == 'J') {
                    dval = lbits;
                } else {
                    dval = Double.longBitsToDouble(lbits);
                }
                result[0] = new Double(dval);
            }
            offset += 4;
        } else {
            // Bad source
            throw new RuntimeException();
        }
        return offset;
    }

    private IRFactory nf;
    private int languageVersion = Context.VERSION_DEFAULT;
    private boolean allowMemberExprAsFunctionName = false;

    private boolean ok; // Did the parse encounter an error?

    private char[] sourceBuffer = new char[128];

// Per script/function source buffer top: parent source does not include a
// nested functions source and uses function index as a reference instead.
    private int sourceTop;

    private ScriptOrFnNode currentScriptOrFn;

    private static final int TOP_LEVEL_SCRIPT_OR_FUNCTION = 0;
    private static final int CONSTRUCTED_FUNCTION = 1;
    private static final int NESTED_FUNCTION = 2;

    // whether to do a debug print of the source information, when
    // decompiling.
    private static final boolean printSource = false;

}

// Exception to unwind
class ParserException extends Exception { }

