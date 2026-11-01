package com.mybatislint.analyzer.xml;

import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;

/**
 * XML ファイルの文字列と DOM を読む。{@link #cached()} の間は、同じファイルを 2 回目以降
 * 読まずに前の結果を返す。
 *
 * <p>ワークスペース全体の解析では、索引づくりと validator ごとに同じ XML を 10 回近く
 * 読み直していた。validator の引数を変えずに読み直しをやめるため、解析 1 回の間だけ
 * 有効なキャッシュをスレッドごとに持つ。validator は DOM を書き換えないので共有してよい。</p>
 *
 * <p>文字列(UTF-8 として読む)と DOM(XML 宣言の encoding に従う)は別々に読み、
 * 失敗したときはそれぞれの例外をそのまま投げ直す。キャッシュの有無で結果が変わらないようにするため。</p>
 */
public final class XmlSources {

    private static final ThreadLocal<Map<Path, Entry>> CACHE = new ThreadLocal<>();
    // cached(Store) の間だけ設定する、解析をまたいで使う保存先。
    private static final ThreadLocal<Store> STORE = new ThreadLocal<>();

    private XmlSources() {
    }

    /** 読んだ結果か、読めなかったときの例外。 */
    private static final class Entry {
        String text;
        IOException textError;
        Document document;
        Exception documentError;
        boolean textLoaded;
        boolean documentLoaded;
    }

    /**
     * 解析をまたいで読んだ XML を覚えておく場所({@code --server} で使う)。更新日時と大きさが
     * 前と同じファイルは読み直さない。
     */
    public static final class Store {
        private final Map<Path, Stored> entries = new HashMap<>();

        private record Stored(FileTime modified, long size, Entry entry) {
        }

        private Entry entryFor(Path key) {
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(key, BasicFileAttributes.class);
            } catch (IOException e) {
                // 読めないファイルは覚えない。読もうとしたときの例外は Entry が覚える。
                entries.remove(key);
                return new Entry();
            }
            Stored stored = entries.get(key);
            if (stored != null && stored.modified().equals(attributes.lastModifiedTime())
                    && stored.size() == attributes.size()) {
                return stored.entry();
            }
            Entry entry = new Entry();
            entries.put(key, new Stored(attributes.lastModifiedTime(), attributes.size(), entry));
            return entry;
        }
    }

    /** 閉じるまでの間、このスレッドで読んだ XML を覚えておく。 */
    public static Scope cached() {
        return cached(null);
    }

    /** {@link #cached()} と同じだが、変わっていないファイルは {@code store} に覚えたものを使う。 */
    public static Scope cached(Store store) {
        Map<Path, Entry> previous = CACHE.get();
        Store previousStore = STORE.get();
        CACHE.set(new HashMap<>());
        STORE.set(store);
        return () -> {
            if (previous == null) {
                CACHE.remove();
            } else {
                CACHE.set(previous);
            }
            if (previousStore == null) {
                STORE.remove();
            } else {
                STORE.set(previousStore);
            }
        };
    }

    /** try-with-resources で使うために、例外を投げない close にする。 */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    public static String readString(Path file) throws IOException {
        Entry entry = entry(file);
        if (entry == null) {
            return Files.readString(file, StandardCharsets.UTF_8);
        }
        if (!entry.textLoaded) {
            entry.textLoaded = true;
            try {
                entry.text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                entry.textError = e;
            }
        }
        if (entry.textError != null) {
            throw entry.textError;
        }
        return entry.text;
    }

    public static Document parse(Path file) throws IOException, SAXException {
        Entry entry = entry(file);
        if (entry == null) {
            return SafeXmlParserFactory.newDocumentBuilder().parse(file.toFile());
        }
        if (!entry.documentLoaded) {
            entry.documentLoaded = true;
            try {
                entry.document = SafeXmlParserFactory.newDocumentBuilder().parse(file.toFile());
            } catch (IOException | SAXException | RuntimeException e) {
                entry.documentError = e;
            }
        }
        if (entry.documentError instanceof IOException e) {
            throw e;
        }
        if (entry.documentError instanceof SAXException e) {
            throw e;
        }
        if (entry.documentError instanceof RuntimeException e) {
            throw e;
        }
        return entry.document;
    }

    private static Entry entry(Path file) {
        Map<Path, Entry> cache = CACHE.get();
        if (cache == null) {
            return null;
        }
        // 1 回の解析の中では、更新日時を見るのは最初の 1 回だけでよい。
        Store store = STORE.get();
        return cache.computeIfAbsent(file.toAbsolutePath().normalize(),
                key -> store == null ? new Entry() : store.entryFor(key));
    }
}
