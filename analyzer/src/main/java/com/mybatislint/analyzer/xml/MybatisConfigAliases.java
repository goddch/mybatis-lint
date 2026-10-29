package com.mybatislint.analyzer.xml;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MyBatis の設定ファイル({@code <configuration>} がルートの XML)から、
 * {@code <typeAlias alias="...">} で登録された alias 名を集める。
 *
 * <p>Spring Boot の {@code mybatis.type-aliases-package} や Java コードでの登録は
 * 読まない。そちらはパッケージ内のクラスの simple name で登録されるので、
 * Java ソースの索引で足りる。</p>
 */
public final class MybatisConfigAliases {

    private MybatisConfigAliases() {
    }

    public static Set<String> collect(List<Path> xmlFiles) {
        Set<String> aliases = new HashSet<>();
        for (Path file : xmlFiles) {
            try {
                Element root = XmlSources.parse(file).getDocumentElement();
                if (root == null || !"configuration".equals(root.getTagName())) {
                    continue;
                }
                NodeList typeAliases = root.getElementsByTagName("typeAlias");
                for (int i = 0; i < typeAliases.getLength(); i++) {
                    String alias = ((Element) typeAliases.item(i)).getAttribute("alias");
                    if (!alias.isBlank()) {
                        aliases.add(alias.trim());
                    }
                }
            } catch (Exception ignored) {
                // 読めない XML は通常の解析で報告する。alias が 1 つ減るだけなので続ける。
            }
        }
        return aliases;
    }
}
