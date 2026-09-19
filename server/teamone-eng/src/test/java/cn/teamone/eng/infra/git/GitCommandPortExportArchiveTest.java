package cn.teamone.eng.infra.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GitCommandPort.exportArchive 纯逻辑单测（M4-INC1）：archive 命令形状钉死 +
 * 最小 tar 解包器（文件/目录/pax 头跳过/符号链接不落地/路径越界拒绝）。
 * 测试自造 tar 字节流（不调真实 git）；untar 只读 name/size/typeflag/prefix 四字段，
 * 故造包器只填这些字段。
 *
 * @author Ivan Yang, 2026-09-19
 */
class GitCommandPortExportArchiveTest {

    @TempDir
    Path dest;

    @Test
    void buildArchiveCommand_shape() {
        List<String> cmd = GitCommandPort.buildArchiveCommand("/gitrepos/teamone/teamone.git", "abc123");
        assertEquals(List.of("git", "--git-dir", "/gitrepos/teamone/teamone.git",
                "archive", "--format=tar", "abc123"), cmd);
    }

    @Test
    void untar_extractsFilesAndDirs_skipsPaxAndSymlink() throws IOException {
        byte[] tar = TarBuilder.create()
                .paxGlobalHeader("{\"comment\":\"41 hex sha\"}")
                .dir("server")
                .dir("server/src")
                .file("server/pom.xml", "<project/>")
                .file("server/src/A.java", "class A {}") // 非对齐尺寸（14 字节）验证补位跳过
                .symlink("server/current", ".")
                .file("README.md", "hello")
                .toBytes();

        GitCommandPort.untar(stream(tar), dest);

        assertEquals("<project/>", Files.readString(dest.resolve("server/pom.xml")));
        assertEquals("class A {}", Files.readString(dest.resolve("server/src/A.java")));
        assertEquals("hello", Files.readString(dest.resolve("README.md")));
        assertTrue(Files.isDirectory(dest.resolve("server/src")));
        assertFalse(Files.exists(dest.resolve("server/current")), "符号链接不落地（防逃逸）");
    }

    @Test
    void untar_rejectsPathTraversal() {
        byte[] tar = TarBuilder.create().file("../escape.txt", "x").toBytes();
        assertThrows(IOException.class, () -> GitCommandPort.untar(stream(tar), dest));
        assertFalse(Files.exists(dest.resolve("../escape.txt")));
    }

    @Test
    void untar_toleratesTrailingZeroBlocks_andEmptyStream() throws IOException {
        TarBuilder builder = TarBuilder.create().file("a.txt", "a");
        byte[] tar = builder.toBytes();
        GitCommandPort.untar(stream(tar), dest);
        assertEquals("a", Files.readString(dest.resolve("a.txt")));

        // 空流：直接返回
        GitCommandPort.untar(new ByteArrayInputStream(new byte[0]), dest);
    }

    private static InputStream stream(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }

    /** 测试专用最小 tar 造包器（ustar 子集：name/size/typeflag；其余字段置零） */
    static final class TarBuilder {

        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        static TarBuilder create() {
            return new TarBuilder();
        }

        TarBuilder file(String name, String content) {
            byte[] data = content.getBytes(StandardCharsets.UTF_8);
            header(name, data.length, '0');
            out.writeBytes(data);
            pad(data.length);
            return this;
        }

        TarBuilder dir(String name) {
            header(name, 0, '5');
            return this;
        }

        /** pax 全局头（git archive 以 commit id 注释产出）：typeflag 'g'，内容须按 size 跳过 */
        TarBuilder paxGlobalHeader(String content) {
            byte[] data = content.getBytes(StandardCharsets.UTF_8);
            header("pax_global_header", data.length, 'g');
            out.writeBytes(data);
            pad(data.length);
            return this;
        }

        TarBuilder symlink(String name, String target) {
            byte[] data = target.getBytes(StandardCharsets.UTF_8);
            header(name, data.length, '2');
            out.writeBytes(data);
            pad(data.length);
            return this;
        }

        byte[] toBytes() {
            out.writeBytes(new byte[512]); // 结尾零块 ×2（untar 读到首个即停）
            out.writeBytes(new byte[512]);
            return out.toByteArray();
        }

        private void header(String name, long size, char typeFlag) {
            byte[] h = new byte[512];
            put(h, 0, name.getBytes(StandardCharsets.UTF_8));
            put(h, 124, String.format("%011o\0", size).getBytes(StandardCharsets.UTF_8));
            h[156] = (byte) typeFlag;
            out.writeBytes(h);
        }

        private void pad(long size) {
            int pad = (int) ((512 - size % 512) % 512);
            out.writeBytes(new byte[pad]);
        }

        private static void put(byte[] header, int off, byte[] data) {
            System.arraycopy(data, 0, header, off, Math.min(data.length, header.length - off));
        }
    }
}
