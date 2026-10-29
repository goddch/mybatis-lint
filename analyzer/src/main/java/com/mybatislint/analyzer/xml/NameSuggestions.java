package com.mybatislint.analyzer.xml;

import java.util.Collection;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * 打ち間違えた名前に近い、正しい名前の候補を探す。
 *
 * <p>候補を出すのは、近さ(大文字小文字を無視した編集距離。隣どうしの入れ替えも 1 と数える)が
 * 名前の長さに対して十分小さく、いちばん近い候補がただ 1 つのときだけにする。
 * 迷う候補を出すと、ワンクリックで別の名前に直してしまうため。</p>
 */
final class NameSuggestions {

    private NameSuggestions() {
    }

    /** いちばん近い候補。なければ null。 */
    static String closest(String name, Collection<String> candidates) {
        return closest(name, candidates, value -> value.toLowerCase(Locale.ROOT));
    }

    /**
     * @param normalize 比べる前に名前をそろえる関数(列の別名なら、大文字小文字とアンダースコアを無視する)
     */
    static String closest(String name, Collection<String> candidates, UnaryOperator<String> normalize) {
        if (name == null || name.isBlank() || candidates == null) {
            return null;
        }
        String target = normalize.apply(name);
        // 3 文字以下の名前は、大文字小文字の違いだけを直す(id と ip のような別の名前に直さない)。
        int limit = target.length() <= 3 ? 0 : Math.min(3, Math.max(1, target.length() / 4));
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        boolean tie = false;
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank() || candidate.equals(name) || candidate.startsWith("_")) {
                continue;
            }
            int distance = distance(target, normalize.apply(candidate));
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
                tie = false;
            } else if (distance == bestDistance && !candidate.equals(best)) {
                tie = true;
            }
        }
        return best != null && !tie && bestDistance <= limit ? best : null;
    }

    /** 隣どうしの入れ替えも 1 回と数える編集距離(optimal string alignment)。 */
    static int distance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }

    /** メッセージの末尾に付ける文。候補がなければ空文字。 */
    static String didYouMean(String suggestion) {
        return suggestion == null ? "" : " Did you mean '" + suggestion + "'?";
    }
}
