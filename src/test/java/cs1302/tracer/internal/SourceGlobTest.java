package cs1302.tracer.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SourceGlobTest {

    @Test
    void matchesCompleteLiteralPathsCaseSensitively() {
        assertMatches("demo/Main.java", List.of("demo/Main.java"),
                List.of("Demo/Main.java", "demo/main.java", "Main.java", "x/demo/Main.java",
                        "demo/Main.java.bak", "demo/Main.jav", "demo/Other.java"));
        assertMatches(".../.hidden.java", List.of(".../.hidden.java"), List.of(".../hidden.java"));
    }

    @Test
    void singleComponentWildcardsIncludeHiddenNamesWithoutCrossingSeparators() {
        assertMatches("demo/*.java", List.of("demo/Main.java", "demo/.java", "demo/.Hidden.java"),
                List.of("demo/deep/Main.java", "demo/Main.JAVA", "Main.java"));
        assertMatches("file?.java", List.of("file1.java", "file😀.java"),
                List.of("file.java", "file12.java", "file/.java"));
        assertMatches("a*b*c", List.of("abc", "aaabbbc", "abbxxc"), List.of("abbxxd", "a/b/c"));
    }

    @Test
    void recursiveWildcardIncludesZeroAndManyDirectoryLevels() {
        assertMatches("demo/**/*.java",
                List.of("demo/Main.java", "demo/deep/Main.java", "demo/one/two/Main.java"),
                List.of("Main.java", "other/demo/Main.java", "demo/Main.txt"));
        assertMatches("**/*.java", List.of("Main.java", "a/Main.java", "a/b/Main.java"),
                List.of("Main.txt", "a.java/Main.txt"));
        assertMatches("demo/**/Main.java", List.of("demo/Main.java", "demo/a/b/Main.java"),
                List.of("demo/a/MyMain.java", "demo/a/Main.jav"));
        assertMatches("demo/**", List.of("demo", "demo/Main.java", "demo/a/b/Main.txt"),
                List.of("demolition", "other/demo"));
        assertMatches("**", List.of("Main.java", "a/b/c", ".hidden"), List.of(""));
    }

    @Test
    void repeatedRecursiveComponentsRemainIndependent() {
        assertMatches("**/**/Main.java", List.of("Main.java", "a/Main.java", "a/b/c/Main.java"),
                List.of("Other.java", "a/Other.java"));
        assertMatches("a/**/**/b/**", List.of("a/b", "a/b/c", "a/x/y/b/z"),
                List.of("ab", "a/x/y/c"));
    }

    @Test
    void alternativesCanContainPathsAndRecursiveComponents() {
        assertMatches("{demo,test/deep}/**/*.java",
                List.of("demo/Main.java", "test/deep/Main.java", "test/deep/more/Main.java"),
                List.of("test/Main.java", "elsewhere/Main.java", "demo/Main.txt"));
        assertMatches("{**,demo}/Main.java",
                List.of("Main.java", "demo/Main.java", "a/b/Main.java"), List.of("Main.txt"));
        assertMatches("root/{one/**,two}/Main.java",
                List.of("root/one/Main.java", "root/one/a/Main.java", "root/two/Main.java"),
                List.of("root/two/a/Main.java", "root/Main.java"));
        assertMatches("root/{one,**}", List.of("root", "root/one", "root/a/b"), List.of("other"));
    }

    @Test
    void alternativesMaySplitComponentsAndSharePrefixesAndSuffixes() {
        assertMatches("{a,b}{c,d}.java", List.of("ac.java", "ad.java", "bc.java", "bd.java"),
                List.of("ab.java", "cd.java", "ac.java.bak"));
        assertMatches("prefix{One,Two,Three}Suffix.java",
                List.of("prefixOneSuffix.java", "prefixTwoSuffix.java", "prefixThreeSuffix.java"),
                List.of("prefixSuffix.java", "One.java"));
        assertMatches("{demo/,test/}Main.java", List.of("demo/Main.java", "test/Main.java"),
                List.of("Main.java", "demo/test/Main.java"));
        assertMatches("{a,b}{/,x/}**/Main.java",
                List.of("a/Main.java", "ax/Main.java", "b/deep/Main.java", "bx/deep/Main.java"),
                List.of("ab/Main.java", "x/Main.java"));
    }

    @Test
    void characterClassesUseInclusiveRangesAndLeadingNegation() {
        assertMatches("[a-cA-C][!0-9].java", List.of("az.java", "BX.java", "c!.java"),
                List.of("a1.java", "Dq.java", "dq.java", "!q.java", "a/.java"));
        assertMatches("[abc].java", List.of("a.java", "b.java", "c.java"),
                List.of("d.java", "A.java", ".java"));
        assertMatches("[-a].java", List.of("-.java", "a.java"), List.of("b.java"));
        assertMatches("[a-].java", List.of("-.java", "a.java"), List.of("b.java"));
        assertMatches("[--0].java", List.of("-.java", "0.java"), List.of("..java", "1.java"));
        assertMatches("[\\--0].java", List.of("-.java", "0.java", "..java"), List.of("1.java"));
        assertMatches("a[.-0]b", List.of("a.b", "a0b"), List.of("a/b", "a-b", "a1b"));
        assertMatches("[!x]", List.of("a", "😀"), List.of("x"));
    }

    @Test
    void metacharactersCanBeEscapedInsideAndOutsideClasses() {
        assertMatches("\\*\\?\\[\\]\\{\\}\\,\\!\\-\\\\",
                List.of("*?[]{},!-\\"), List.of("*?[]{},!-", "anything"));
        assertMatches("{one\\,two,three}.java", List.of("one,two.java", "three.java"),
                List.of("one.java", "two.java"));
        assertMatches("[\\]\\[\\!\\-\\?\\*\\{\\}\\\\,].java",
                List.of("].java", "[.java", "!.java", "-.java", "?.java", "*.java",
                        "{.java", "}.java", "\\.java", ",.java"), List.of("a.java"));
        assertMatches("literal\\**.java", List.of("literal*.java", "literal*Extra.java"),
                List.of("literalExtra.java"));
    }

    @Test
    void unicodeWildcardsAndRangesConsumeWholeCodePoints() {
        assertMatches("?.java", List.of("😀.java", "é.java"), List.of("ab.java", ".java"));
        assertMatches("??.java", List.of("😀é.java"), List.of("😀.java"));
        assertMatches("[😀-🙏].java", List.of("😀.java", "😁.java", "🙏.java"),
                List.of("🀄.java", "🚀.java", "x.java"));
        assertMatches("😀{α,β}.java", List.of("😀α.java", "😀β.java"), List.of("😀γ.java"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "/", "/Main.java", "Main.java/", "a//b", ".", "..", "./a",
            "a/../b", "a/.", "{a,../b}", "{a/,/b}c", "{.,..}/Main.java", "{a,b}/" })
    void rejectsPatternsWithInvalidPathComponents(String pattern) {
        assertThatIllegalArgumentException().isThrownBy(() -> SourceGlob.compile(pattern))
                .withMessageContaining("Invalid source glob '" + pattern + "'")
                .withMessageContaining("no empty, . or .. components");
    }

    @ParameterizedTest
    @ValueSource(strings = { "a**", "**a", "***", "a/***.java", "demo/Foo**Bar.java",
            "**{a,b}", "{a,b}**", "{**,a}x", "*{**,a}", "{a/**,b}/c**" })
    void rejectsRecursiveWildcardsInsideComponents(String pattern) {
        assertThatIllegalArgumentException().isThrownBy(() -> SourceGlob.compile(pattern))
                .withMessageContaining("** must occupy an entire path component");
    }

    @ParameterizedTest
    @ValueSource(strings = { "{", "{}", "{a}", "{a,}", "{,a}", "{a,,b}", "{a,b", "{a,{b,c}}",
            "a}", "a,b", "a]", "[", "[]", "[!]", "[a", "[a-", "[z-a]", "[[a]", "[a/b]",
            "a\\", "a\\q", "a\\/b", "[\\", "[\\q]", "[a-\\]" })
    void rejectsMalformedSyntaxWithPatternContext(String pattern) {
        assertThatIllegalArgumentException().isThrownBy(() -> SourceGlob.compile(pattern))
                .withMessageContaining("Invalid source glob '" + pattern + "'");
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "/", "/a", "a/", "a//b", ".", "..", "a/./b", "a/../b" })
    void malformedCandidatePathsDoNotMatchEvenRecursiveWildcards(String path) {
        assertThat(SourceGlob.compile("**").matches(path)).isFalse();
    }

    @Test
    void requiresNonNullPatternAndCandidate() {
        assertThatNullPointerException().isThrownBy(() -> SourceGlob.compile(null))
                .withMessage("glob");
        assertThatNullPointerException().isThrownBy(() -> SourceGlob.compile("**").matches(null))
                .withMessage("normalizedRelativePath");
    }

    @Test
    void largeAlternativeProductsAndWildcardFailuresStayBounded() {
        assertTimeout(Duration.ofSeconds(5), () -> {
            SourceGlob alternatives = SourceGlob.compile("{a,b}".repeat(128) + ".java");
            assertThat(alternatives.matches("a".repeat(128) + ".java")).isTrue();
            assertThat(alternatives.matches("a".repeat(128) + ".txt")).isFalse();
            SourceGlob stars = SourceGlob.compile("*a".repeat(128) + "z.java");
            assertThat(stars.matches("a".repeat(256) + "y.java")).isFalse();
            SourceGlob recursive = SourceGlob.compile("**/".repeat(64) + "Main.java");
            assertThat(recursive.matches("dir/".repeat(128) + "Main.java")).isTrue();
            assertThat(recursive.matches("dir/".repeat(128) + "Other.java")).isFalse();
        });
    }

    private static void assertMatches(String pattern, List<String> matches, List<String> misses) {
        SourceGlob glob = SourceGlob.compile(pattern);
        for (String path : matches) {
            assertThat(glob.matches(path)).as("%s matches %s", pattern, path).isTrue();
        }
        for (String path : misses) {
            assertThat(glob.matches(path)).as("%s does not match %s", pattern, path).isFalse();
        }
    }

}
