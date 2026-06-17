package net.irisshaders.iris.shaderpack.option.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import net.minecraft.locale.Language;

/**
 * Isolated search processor utility to prevent Mixin bytecode bloat.
 */
public class ShaderSearchEngine {
	private static final String WHOLE_WORD_REGEX = "(?<=^|[^a-zA-Z0-9])%s(?=$|[^a-zA-Z0-9])";
	private static final String STARTS_WITH_REGEX = "(?<=^|[^a-zA-Z0-9])%s";

	/**
	 * Evaluates an individual option element using pure Regex priority matching.
	 */
	public static int computeMatchTier(OptionMenuOptionElement element, String query, Language languageEngine) {
		if (element == null || element.optionId == null) {
			return 0;
		}

		String trimmedQuery = query.toLowerCase(Locale.ROOT).trim();
		String escapedQuery = Pattern.quote(trimmedQuery);

		// Compile our matching targets
		Pattern wholeWordPat = Pattern.compile(String.format(WHOLE_WORD_REGEX, escapedQuery));
		Pattern startsWithPat = Pattern.compile(String.format(STARTS_WITH_REGEX, escapedQuery));

		// Gather lowercase text sources
		String nameKey = "option." + element.optionId;
		String readableName = languageEngine.has(nameKey) ? languageEngine.getOrDefault(nameKey).toLowerCase(Locale.ROOT) : "";
		String rawId = element.optionId.toLowerCase(Locale.ROOT);
		String commentKey = "option." + element.optionId + ".comment";
		String commentText = languageEngine.has(commentKey) ? languageEngine.getOrDefault(commentKey).toLowerCase(Locale.ROOT) : "";

		// =========================================================================
		// PHASE 1: WHOLE WORD BOUNDARY MATCHES (Tiers 1 - 3)
		// =========================================================================
		if (!readableName.isEmpty() && wholeWordPat.matcher(readableName).find()) return 1;
		if (wholeWordPat.matcher(rawId).find()) return 2;
		if (!commentText.isEmpty() && wholeWordPat.matcher(commentText).find()) return 3;

		// =========================================================================
		// PHASE 2: STARTS WITH WORD MATCHES (Tiers 4 - 6)
		// =========================================================================
		if (!readableName.isEmpty() && startsWithPat.matcher(readableName).find()) return 4;
		if (startsWithPat.matcher(rawId).find()) return 5;
		if (!commentText.isEmpty() && startsWithPat.matcher(commentText).find()) return 6;

		// =========================================================================
		// PHASE 3: CONTAINS FALLBACK MATCHES (Tiers 7 - 9)
		// =========================================================================
		if (!readableName.isEmpty() && readableName.contains(trimmedQuery)) return 7;
		if (rawId.contains(trimmedQuery)) return 8;
		if (!commentText.isEmpty() && commentText.contains(trimmedQuery)) return 9;

		return 0; // No match found
	}

	/**
	 * Flattens and filters duplicate elements from the active options registration pool.
	 */
	public static List<OptionMenuOptionElement> getAllOptionsFlattened(List<OptionMenuOptionElement> usedOptionElements) {
		List<OptionMenuOptionElement> flatList = new ArrayList<>();
		List<String> seenOptionIds = new ArrayList<>();

		for (OptionMenuOptionElement element : usedOptionElements) {
			if (element == null) continue;
			String id = element.optionId != null ? element.optionId : element.toString();

			if (!seenOptionIds.contains(id)) {
				seenOptionIds.add(id);
				flatList.add(element);
			}
		}
		return flatList;
	}

	/**
	 * Nested static companion class to hold scored entries.
	 */
	public static class ScoredOptionElement implements Comparable<ScoredOptionElement> {
		private final OptionMenuOptionElement element;
		private final int matchTier;

		public ScoredOptionElement(OptionMenuOptionElement element, int matchTier) {
			this.element = element;
			this.matchTier = matchTier;
		}

		public OptionMenuOptionElement getElement() {
			return this.element;
		}

		public int getMatchTier() {
			return this.matchTier;
		}

		@Override
		public int compareTo(ScoredOptionElement other) {
			if (this.matchTier != other.matchTier) {
				return Integer.compare(this.matchTier, other.matchTier);
			}
			if (this.element != null && other.element != null &&
				this.element.optionId != null && other.element.optionId != null) {
				return this.element.optionId.compareTo(other.element.optionId);
			}
			return 0;
		}
	}
}
