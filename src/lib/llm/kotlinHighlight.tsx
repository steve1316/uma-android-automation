/**
 * Lightweight Kotlin syntax highlighter for the Ask the Docs page citation cards.
 *
 * `react-native-marked` cannot render syntax-highlighted code blocks on its own, and pulling in a full
 * highlighter (Prism, highlight.js, Shiki) would balloon the bundle for what is just code-citation rendering.
 * This file ships a single-pass regex tokenizer plus two themed palettes (light/dark) that cover Kotlin well
 * enough for excerpt display - it is NOT a full Kotlin parser and does not handle nested string interpolation,
 * context-sensitive `it`/`field` highlighting, or label syntax.
 */
import React from "react"
import { Text, type TextStyle } from "react-native"

/**
 * Reserved Kotlin keywords plus the soft modifiers commonly seen in declarations. Used by `tokenize` to mark a
 * bare identifier as a keyword token. Keep in sorted order so additions are easy to spot in diffs.
 */
const KEYWORDS = new Set([
    "abstract",
    "actual",
    "annotation",
    "as",
    "break",
    "by",
    "catch",
    "class",
    "companion",
    "const",
    "constructor",
    "continue",
    "crossinline",
    "data",
    "do",
    "dynamic",
    "else",
    "enum",
    "expect",
    "external",
    "final",
    "finally",
    "for",
    "fun",
    "get",
    "if",
    "import",
    "in",
    "infix",
    "init",
    "inline",
    "inner",
    "interface",
    "internal",
    "is",
    "lateinit",
    "noinline",
    "object",
    "open",
    "operator",
    "out",
    "override",
    "package",
    "private",
    "property",
    "protected",
    "public",
    "receiver",
    "reified",
    "return",
    "sealed",
    "set",
    "setparam",
    "super",
    "suspend",
    "tailrec",
    "this",
    "throw",
    "try",
    "typealias",
    "typeof",
    "val",
    "var",
    "vararg",
    "when",
    "where",
    "while",
    "yield",
])

/** Boolean and null literals styled distinctly from regular keywords (e.g. blue in the dark palette). */
const LITERALS = new Set(["true", "false", "null"])

/**
 * Token classification produced by `tokenize` and consumed by `KotlinCode`. `plain` covers whitespace,
 * punctuation, and any identifier that didn't match a more specific category.
 */
type TokenKind = "comment" | "string" | "number" | "keyword" | "literal" | "type" | "annotation" | "plain"

/**
 * Color palette for the highlighter. Each field is the foreground color used when `tokenize` classifies a
 * token as the corresponding `TokenKind`; new token kinds must add a matching field here so the palette stays
 * exhaustive.
 */
export interface KotlinPalette {
    /** Default foreground color for plain text and the outer wrapper Text. */
    text: string
    /** Color for `//` line comments and block comments. */
    comment: string
    /** Color for single-, double-, and triple-quoted string literals (and char literals). */
    string: string
    /** Color for integer, hex, and floating-point number literals (with optional `L`/`F` suffix). */
    number: string
    /** Color for reserved Kotlin keywords from `KEYWORDS`. */
    keyword: string
    /** Color for the boolean and null literals in `LITERALS`. */
    literal: string
    /** Color for capitalized identifiers (treated as type names) and backticked identifiers. */
    type: string
    /** Color for `@Annotation` markers. */
    annotation: string
}

/** VSCode-ish dark palette tuned for the dark `muted` background used by code citations. */
export const DARK_PALETTE: KotlinPalette = {
    text: "#e6edf3",
    comment: "#8b949e",
    string: "#a5d6ff",
    number: "#79c0ff",
    keyword: "#ff7b72",
    literal: "#79c0ff",
    type: "#7ee787",
    annotation: "#d2a8ff",
}

/** GitHub light palette for the light `muted` background. */
export const LIGHT_PALETTE: KotlinPalette = {
    text: "#1f2328",
    comment: "#6e7781",
    string: "#0a3069",
    number: "#0550ae",
    keyword: "#cf222e",
    literal: "#0550ae",
    type: "#116329",
    annotation: "#8250df",
}

/** Single-pass tokenizer. Order in the alternation matters: triple-quoted strings before regular strings,
 *  block comments before line comments don't conflict but block must allow newlines, etc. The fallback "any
 *  other character" group catches whitespace and punctuation as plain text. */
const TOKEN_RE =
    /("""[\s\S]*?""")|(\/\/[^\n]*)|(\/\*[\s\S]*?\*\/)|("(?:\\.|[^"\\\n])*")|('(?:\\.|[^'\\])')|(`[^`\n]+`)|(@[A-Za-z_][A-Za-z0-9_]*)|(0[xX][0-9a-fA-F_]+[Ll]?|\d[\d_]*\.\d[\d_]*[fFLl]?|\d[\d_]*[fFLl]?)|([A-Za-z_][A-Za-z0-9_]*)|([\s\S])/g

/** One classified slice of source text emitted by `tokenize`. */
interface Token {
    /** What flavor of source construct this slice represents; drives palette lookup in `KotlinCode`. */
    kind: TokenKind
    /** Verbatim characters from the input source, preserving whitespace and original casing. */
    value: string
}

/**
 * Lex `src` into an ordered list of `Token`s in a single regex pass.
 *
 * The matcher walks left-to-right; each successful match is classified by which alternation group fired, and
 * any characters skipped between the previous match and the current one are emitted as a `plain` token so the
 * full input is preserved exactly. `TOKEN_RE.lastIndex` is reset on entry to make the function safe against
 * the global regex's stateful lastIndex from a prior invocation.
 *
 * @param src Kotlin source text to tokenize. May contain newlines; comments and triple-quoted strings span them.
 * @returns Ordered tokens whose concatenated `value`s recreate `src` verbatim.
 */
function tokenize(src: string): Token[] {
    const tokens: Token[] = []
    let m: RegExpExecArray | null
    let lastIndex = 0
    TOKEN_RE.lastIndex = 0
    while ((m = TOKEN_RE.exec(src)) !== null) {
        if (m.index > lastIndex) tokens.push({ kind: "plain", value: src.slice(lastIndex, m.index) })
        const [full, tripleStr, lineCom, blockCom, dqStr, sqStr, btIdent, anno, num, ident] = m
        let kind: TokenKind = "plain"
        if (tripleStr || dqStr || sqStr) kind = "string"
        else if (lineCom || blockCom) kind = "comment"
        else if (anno) kind = "annotation"
        else if (num) kind = "number"
        else if (btIdent) kind = "type"
        else if (ident) {
            if (KEYWORDS.has(ident)) kind = "keyword"
            else if (LITERALS.has(ident)) kind = "literal"
            else if (/^[A-Z]/.test(ident)) kind = "type"
            else kind = "plain"
        }
        tokens.push({ kind, value: full })
        lastIndex = m.index + full.length
    }
    if (lastIndex < src.length) tokens.push({ kind: "plain", value: src.slice(lastIndex) })
    return tokens
}

/** Props for `KotlinCode`. */
interface KotlinCodeProps {
    /** Kotlin source to render. Newlines are preserved; the parent should wrap or scroll as needed. */
    text: string
    /** Color scheme to apply; pass `DARK_PALETTE` or `LIGHT_PALETTE` (or a custom palette) based on the active theme. */
    palette: KotlinPalette
    /** Optional `Text` style applied to the outer wrapper - typically used to override font size or line height. */
    style?: TextStyle
}

/**
 * Renders `text` as syntax-highlighted Kotlin via nested Text spans. The outer Text owns layout (font, line
 * height, padding from the parent View) and the inner spans only set `color`.
 *
 * @param text Kotlin source to render; tokenization is memoized on this prop.
 * @param palette Color scheme to apply per token kind. Pass `DARK_PALETTE` or `LIGHT_PALETTE`.
 * @param style Optional `Text` style merged onto the outer wrapper - typically used to override font size or
 *   line height for compact citation cards.
 * @returns A React Native `Text` element tree representing the highlighted source.
 */
export function KotlinCode({ text, palette, style }: KotlinCodeProps) {
    const tokens = React.useMemo(() => tokenize(text), [text])
    return (
        <Text style={[{ color: palette.text, fontFamily: "monospace" }, style]}>
            {tokens.map((t, i) => (
                <Text key={i} style={t.kind === "plain" ? undefined : { color: palette[t.kind] }}>
                    {t.value}
                </Text>
            ))}
        </Text>
    )
}
