import * as fs from "node:fs"
import * as os from "node:os"
import * as path from "node:path"
import { chunkKotlinFile } from "../kotlinChunker"

let tmpDir: string
beforeAll(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "kchunk-"))
})
afterAll(() => {
    fs.rmSync(tmpDir, { recursive: true, force: true })
})

function writeKt(filename: string, contents: string): string {
    const p = path.join(tmpDir, filename)
    fs.writeFileSync(p, contents, "utf-8")
    return p
}

describe("chunkKotlinFile", () => {
    it("emits a class header chunk that includes leading KDoc and the declaration line", () => {
        const file = writeKt(
            "Sample.kt",
            [
                "package com.example",
                "",
                "/**",
                " * A sample class doing sample things.",
                " */",
                "class Sample(private val x: Int) {",
                "    /** Returns x doubled. */",
                "    fun double(): Int = x * 2",
                "}",
                "",
            ].join("\n")
        )
        const chunks = chunkKotlinFile(file)
        expect(chunks.length).toBeGreaterThanOrEqual(2)

        const classHeader = chunks.find((c) => c.id === "Sample.kt#Sample")
        expect(classHeader).toBeDefined()
        expect(classHeader!.heading).toBe("Sample")
        expect(classHeader!.text).toMatch(/A sample class doing sample things/)
        expect(classHeader!.text).toMatch(/class Sample\(private val x: Int\)/)
        expect(classHeader!.kind).toBe("code")

        const method = chunks.find((c) => c.id === "Sample.kt#Sample.double")
        expect(method).toBeDefined()
        expect(method!.heading).toBe("Sample › double")
        expect(method!.text).toMatch(/Returns x doubled/)
        expect(method!.text).toMatch(/fun double\(\): Int = x \* 2/)
    })

    it("captures nested class hierarchy in the heading", () => {
        const file = writeKt("Outer.kt", ["package com.example", "", "class Outer {", "    /** Inner thing. */", "    class Inner {", "        fun ping() = 1", "    }", "}", ""].join("\n"))
        const chunks = chunkKotlinFile(file)
        const inner = chunks.find((c) => c.id === "Outer.kt#Outer.Inner")
        expect(inner).toBeDefined()
        expect(inner!.heading).toBe("Outer › Inner")
        expect(inner!.text).toMatch(/Inner thing/)
        const ping = chunks.find((c) => c.id === "Outer.kt#Outer.Inner.ping")
        expect(ping).toBeDefined()
        expect(ping!.heading).toBe("Outer › Inner › ping")
    })

    it("drops bare companion objects with no KDoc", () => {
        const file = writeKt("Tiny.kt", ["package com.example", "", "class Tiny {", "    companion object {", "        const val FOO = 1", "    }", "}", ""].join("\n"))
        const chunks = chunkKotlinFile(file)
        const companion = chunks.find((c) => c.id.endsWith(".Companion"))
        expect(companion).toBeUndefined()
    })

    it("keeps a companion object header when it has KDoc", () => {
        const file = writeKt(
            "Bigger.kt",
            ["package com.example", "", "class Bigger {", "    /** Cached lookups. */", "    companion object {", "        const val FOO = 1", "    }", "}", ""].join("\n")
        )
        const chunks = chunkKotlinFile(file)
        const companion = chunks.find((c) => c.id === "Bigger.kt#Bigger.Companion")
        expect(companion).toBeDefined()
        expect(companion!.text).toMatch(/Cached lookups/)
    })

    it("tags every chunk with kind=code", () => {
        const file = writeKt("Plain.kt", ["package com.example", "", "class Plain {", "    fun a() = 1", "    fun b() = 2", "}", ""].join("\n"))
        const chunks = chunkKotlinFile(file)
        expect(chunks.length).toBeGreaterThan(0)
        for (const c of chunks) expect(c.kind).toBe("code")
    })
})
