package com.p2s.agent.script;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rhino 1.9.2 runs these snippets wrongly or not at all without the rewrite (verified while
 * choosing the engine): const in loop bodies keeps its first value, closures over loop-head
 * variables see the last value, and for (const x of ...) inside a function is a syntax error.
 */
class ScriptSourceRewriterTest {
    private static String run(String source) throws Exception {
        try (Context cx = new ContextFactory().enterContext()) {
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setInterpretedMode(true);
            String rewritten = ScriptSourceRewriter.rewrite(source);
            return Context.toString(cx.evaluateString(cx.initSafeStandardObjects(), "(function(){ " + rewritten + "\n})()", "t", 1, null));
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
            const in loop body          | let s=0; for (let i=0;i<3;i++){ const k=i; s+=k;} return s                                   | 3
            for-const-of in function    | return (function(){ let t=0; for (const v of [1,2,3]) t+=v; return t })()                     | 6
            closure over for head       | var fs=[]; for (let i=0;i<3;i++){ fs.push(()=>i) } return fs.map(f=>f()).join(';')             | 0;1;2
            closure over for-of head    | var fs=[]; for (const v of [0,1,2]) fs.push(function(){ return v }); return fs.map(f=>f()).join(';') | 0;1;2
            destructuring for-of head   | var fs=[]; for (const [k,v] of [[1,2],[3,4]]) { fs.push(() => k*v) } return fs.map(f=>f()).join(';') | 2;12
            two head variables          | var fs=[]; for (let i=0, j=10; i<2; i++, j--) { fs.push(() => i+':'+j) } return fs.map(f=>f()).join(';') | 0:10;1:9
            nested loops                | var fs=[]; for (let y=0;y<2;y++){ for (let x=0;x<2;x++){ fs.push(()=>x+''+y) } } return fs.map(f=>f()).join(';') | 00;10;01;11
            continue keeps working      | var fs=[]; for (let i=0;i<4;i++){ if (i%2) continue; fs.push(()=>i) } return fs.map(f=>f()).join(';') | 0;2
            property names untouched    | var fs=[], o={i:7}; for (let i=0;i<2;i++){ fs.push(()=>o.i+i) } return fs.map(f=>f()).join(';') | 7;8
            single statement body       | var fs=[]; for (let i=0;i<3;i++) fs.push(()=>i); return fs.map(f=>f()).join(';')              | 0;1;2
            async and await removed     | const f = async (x) => x*2; return await f(3)                                                  | 6
            await as property name      | const o={await:1}; return o.await                                                             | 1
            regex literal kept          | const r=/const\\s+[a-z]/; return r.test('const x')                                             | true
            """)
    void runsCorrectlyAfterRewrite(String name, String source, String expected) throws Exception {
        assertEquals(expected, run(source));
    }

    @Test
    void stringsTemplatesAndCommentsAreNotRewritten() throws Exception {
        assertEquals("const await|x const 1 const",
                run("const s='const await'; /* const */ const t=`x ${'const'} ${ {a:1}.a } const`; return s+'|'+t"));
    }

    @Test
    void closureAndAssignedLoopVariableIsRejectedInsteadOfMisrun() {
        assertThrows(ScriptSourceRewriter.RejectedScriptException.class,
                () -> ScriptSourceRewriter.rewrite("var fs=[]; for (let i=0;i<5;i++){ fs.push(()=>i); i++ }"));
    }

    @Test
    void rewritingKeepsLineNumbers() throws Exception {
        String source = "const a = 1;\nfor (let i=0;i<2;i++){ setTimeout(()=>i) }\nconst b = 2;";
        assertEquals(source.lines().count(), ScriptSourceRewriter.rewrite(source).lines().count());
    }
}
