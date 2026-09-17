package org.apve;

public class SimilarityChecker {

    public static boolean containsBadRoot(String word, String root) {
        if (word == null || root == null) return false;
        return word.contains(root);
    }

    public static double getSimilarityRatio(String str1, String str2, double minThreshold) {
        if (str1 == null || str2 == null) return 0.0;
        if (str1.equals(str2)) return 1.0;

        int len1 = str1.length();
        int len2 = str2.length();
        if (len1 == 0 || len2 == 0) return 0.0;

        if (str1.charAt(0) != str2.charAt(0)) {
        minThreshold = Math.max(minThreshold, 0.85);
    }

        int maxLength = Math.max(len1, len2);

        double maxPossibleSim = 1.0 - ((double) Math.abs(len1 - len2) / maxLength);
        if (maxPossibleSim < minThreshold) {
            return 0.0; 
        }

        int[] prev = new int[len2 + 1];
        int[] curr = new int[len2 + 1];

        for (int j = 0; j <= len2; j++) prev[j] = j;

        for (int i = 1; i <= len1; i++) {
            curr[0] = i;
            for (int j = 1; j <= len2; j++) {
                int cost = (str1.charAt(i - 1) == str2.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(
                        Math.min(prev[j] + 1, curr[j - 1] + 1),
                        prev[j - 1] + cost
                );
            }
            int[] temp = prev;
            prev = curr;
            curr = temp;
        }

        return 1.0 - ((double) prev[len2] / maxLength);
    }
}
