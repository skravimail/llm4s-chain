package org.l4j.template.llm4s.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import munit.FunSuite

/** Guards the documentation layout introduced by PR-16.
  *
  * The intent isn't to test markdown rendering — it's to keep the canonical
  * filenames stable so links in PRs / external bookmarks don't silently rot
  * if someone renames a file.
  */
class DocsLayoutSpec extends FunSuite:

  // Tests run from a sub-project working dir; walk up to find the repo root.
  private val repoRoot: Path =
    val cwd = Paths.get("").toAbsolutePath
    LazyList.iterate(cwd)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.exists(p.resolve("build.sbt")))
      .getOrElse(sys.error(s"Could not find repository root from $cwd"))

  private def assertExists(rel: String): Unit =
    val p = repoRoot.resolve(rel)
    assert(Files.exists(p), s"$rel must exist (looked at $p)")

  private def assertReadmeMentions(needle: String): Unit =
    val text = Files.readString(repoRoot.resolve("README.md"))
    assert(text.contains(needle), s"README.md should reference '$needle'")

  test("canonical documentation files exist at expected paths") {
    assertExists("README.md")
    assertExists("CHANGELOG.md")
    assertExists("CODE_REVIEW.md")
    assertExists("docs/USAGE.md")
  }

  test("README uses the llm4s-chain project name and points at canonical docs") {
    val text = Files.readString(repoRoot.resolve("README.md"))
    assert(
      text.startsWith("llm4s-chain"),
      "README.md title should be llm4s-chain (not the legacy llm4s-chain name)",
    )
    assert(
      !text.contains("llm4s-chain"),
      "README.md still contains the legacy llm4s-chain project name",
    )
    assertReadmeMentions("docs/USAGE.md")
    assertReadmeMentions("CHANGELOG.md")
    assert(
      !text.contains("Usage_Readme.md"),
      "README.md still references the legacy Usage_Readme.md path",
    )
    assert(
      !text.contains("Readme_PR_plan.md"),
      "README.md still references the legacy Readme_PR_plan.md path",
    )
  }

  test("USAGE guide does not reference stale branch/doc names or removed APIs") {
    val text = Files.readString(repoRoot.resolve("docs/USAGE.md"))
    assert(
      !text.contains("l4jOnly_codex"),
      "docs/USAGE.md still references the old branch name",
    )
    assert(
      !text.contains("Readme_PR_plan.md"),
      "docs/USAGE.md still references the legacy Readme_PR_plan.md path",
    )
    assert(
      !text.contains("runtime.chatWithMemory"),
      "docs/USAGE.md still references the removed AiRuntime.chatWithMemory API",
    )
  }
