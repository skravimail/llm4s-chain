package org.l4j.template.llm4s.structured

import munit.FunSuite

class JsonExtractorSpec extends FunSuite:

  test("happy path: clean JSON parses unchanged") {
    val r = JsonExtractor.extract("""{"a":1,"b":"two"}""")
    assertEquals(r.map(_("a").num.toInt), Right(1))
    assertEquals(r.map(_("b").str), Right("two"))
  }

  test("leading/trailing whitespace tolerated") {
    val r = JsonExtractor.extract("   \n  {\"a\":1}\t\n")
    assertEquals(r.map(_("a").num.toInt), Right(1))
  }

  test("markdown fence with language tag: ```json {...} ```") {
    val payload =
      """```json
        |{"score": 42, "feedback": "ok"}
        |```""".stripMargin
    val r = JsonExtractor.extract(payload)
    assertEquals(r.map(_("score").num.toInt), Right(42))
    assertEquals(r.map(_("feedback").str), Right("ok"))
  }

  test("markdown fence without language tag: ``` {...} ```") {
    val payload =
      """```
        |{"a":1}
        |```""".stripMargin
    assertEquals(JsonExtractor.extract(payload).map(_("a").num.toInt), Right(1))
  }

  test("prose prefix: 'Here is the JSON: {...}'") {
    val payload = """Sure! Here is the JSON you asked for: {"score": 7, "feedback": "good"}"""
    val r = JsonExtractor.extract(payload)
    assertEquals(r.map(_("score").num.toInt), Right(7))
  }

  test("prose suffix: '{...} Let me know if you need more.'") {
    val payload = """{"score":5,"feedback":"meh"} Let me know if you'd like a deeper review."""
    val r = JsonExtractor.extract(payload)
    assertEquals(r.map(_("feedback").str), Right("meh"))
  }

  test("prose around fenced JSON survives") {
    val payload =
      """Sure, here's my review:
        |```json
        |{"score":8,"feedback":"strong"}
        |```
        |Let me know if you need more detail.""".stripMargin
    val r = JsonExtractor.extract(payload)
    assertEquals(r.map(_("score").num.toInt), Right(8))
  }

  test("nested objects: brace depth counts correctly") {
    val payload = """blah {"outer":{"inner":{"k":1}}} suffix"""
    val r = JsonExtractor.extract(payload)
    assertEquals(r.map(_("outer")("inner")("k").num.toInt), Right(1))
  }

  test("string containing { and } must not confuse the brace counter") {
    val payload = """{"text": "this string has {braces} inside", "n": 3}"""
    val r = JsonExtractor.extract(payload)
    assertEquals(r.map(_("text").str), Right("this string has {braces} inside"))
    assertEquals(r.map(_("n").num.toInt), Right(3))
  }

  test("escaped quote in string: \\\" does not end the string early") {
    val payload = """blah {"q": "she said \"hi\" then left", "n": 1} extra"""
    val r = JsonExtractor.extract(payload)
    assertEquals(r.map(_("q").str), Right("""she said "hi" then left"""))
    assertEquals(r.map(_("n").num.toInt), Right(1))
  }

  test("array literal extracted when no object is present") {
    val payload = """The list is: ["a","b","c"] — done."""
    val r = JsonExtractor.extract(payload)
    assertEquals(r.map(_.arr.toList.map(_.str)), Right(List("a", "b", "c")))
  }

  test("totally non-JSON input returns Left with a preview") {
    val r = JsonExtractor.extract("there is no JSON anywhere in this sentence")
    assert(r.isLeft)
    assert(r.left.toOption.exists(_.contains("could not extract")))
  }

  test("malformed JSON inside a balanced block returns Left") {
    // Balanced braces, but the contents aren't valid JSON.
    val payload = """{this is not: valid json}"""
    val r = JsonExtractor.extract(payload)
    assert(r.isLeft)
  }

  test("DerivedStructuredCodec.decode routes through the extractor (regression)") {
    import org.l4j.template.llm4s.tools.SchemaEncoder
    import org.l4j.template.llm4s.tools.ValueDecoder

    final case class Review(score: Int, feedback: String)
        derives StructuredCodec, SchemaEncoder, ValueDecoder

    val codec = summon[StructuredCodec[Review]]

    // The model wrapped its answer in markdown — previously this would
    // fail with `expected json value got "`" at index 0`.
    val proseWrapped =
      """Here you go:
        |```json
        |{"score": 91, "feedback": "Excellent fit"}
        |```""".stripMargin

    assertEquals(codec.decode(proseWrapped), Right(Review(91, "Excellent fit")))
  }
