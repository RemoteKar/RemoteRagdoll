package org.EIQUI.RemoteRagdoll;

import com.github.stephengold.joltjni.Jolt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;

import static org.EIQUI.RemoteRagdoll.main.that;

/**
 * Jolt 네이티브 로딩 담당.
 *
 * jolt-jni 의 메인 jar 에는 자바 바인딩만 있고 네이티브가 없다. 네이티브는 classifier jar(ReleaseSp)로 따로 오며
 * jar 안에서 {@code <os>/<arch>/com/github/stephengold/<lib>} 경로에 들어있다(snaploader 규약).
 * jolt-jni 자체는 로더를 제공하지 않으므로(Jolt 의 registerTypes 등은 전부 native 메서드라 로드가 선행돼야 한다)
 * 여기서 직접 리소스를 꺼내 {@link System#load(String)} 한다.
 *
 * 추출 위치는 플러그인 데이터 폴더 아래 natives/ 다. 임시 폴더를 쓰면 재시작마다 다시 풀어야 하고
 * OS 가 청소해버릴 수 있어서 데이터 폴더에 두고 크기가 같으면 재사용한다.
 *
 * 지금 번들된 플랫폼은 Windows x86-64 / Linux x86-64 둘뿐이다. 그 외에서는 물리 기능만 꺼지고
 * 서버 기동 자체는 막지 않는다.
 */
public final class JoltNatives {

    private JoltNatives() {}

    private static boolean attempted = false;
    private static boolean available = false;

    /** 네이티브 로드 + Jolt 초기화까지 끝났으면 true. 실패해도 예외를 던지지 않는다. */
    public static synchronized boolean isAvailable() {
        if (!attempted) {
            attempted = true;
            try {
                loadAndInit();
                available = true;
                that.getLogger().info("[Physics] Jolt " + Jolt.versionString() + " 준비 완료");
            } catch (Throwable t) {
                available = false;
                that.getLogger().log(Level.WARNING,
                        "[Physics] Jolt 네이티브 로드 실패 - 물리 기능이 비활성화됩니다", t);
            }
        }
        return available;
    }

    private static void loadAndInit() throws IOException {
        String resource = resourcePath();
        String fileName = resource.substring(resource.lastIndexOf('/') + 1);

        Path dir = that.getDataFolder().toPath().resolve("natives");
        Files.createDirectories(dir);
        Path target = dir.resolve(fileName);

        try (InputStream in = JoltNatives.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("네이티브 리소스가 jar 안에 없습니다: " + resource);
            }
            // 이미 같은 크기로 풀려있으면 그대로 쓴다. (실행 중 파일은 Windows 에서 덮어쓸 수 없기도 하다)
            byte[] bytes = in.readAllBytes();
            if (!Files.exists(target) || Files.size(target) != bytes.length) {
                Files.write(target, bytes);
            }
        }

        System.load(target.toAbsolutePath().toString());

        Jolt.registerDefaultAllocator();
        Jolt.installDefaultAssertCallback();
        Jolt.installDefaultTraceCallback();
        Jolt.newFactory();
        Jolt.registerTypes();
    }

    private static String resourcePath() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();

        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        if (!x64) {
            throw new UnsupportedOperationException("지원하지 않는 아키텍처: " + arch + " (x86-64 만 번들되어 있습니다)");
        }
        if (os.contains("win")) {
            return "windows/x86-64/com/github/stephengold/joltjni.dll";
        }
        if (os.contains("linux")) {
            return "linux/x86-64/com/github/stephengold/libjoltjni.so";
        }
        throw new UnsupportedOperationException("지원하지 않는 OS: " + os);
    }

    /**
     * 추출된 네이티브 파일 경로 정리용. 서버 종료 시 굳이 지울 필요는 없어서 호출하지 않지만,
     * 버전 업으로 파일이 바뀔 때 수동 정리가 필요하면 쓴다.
     */
    public static void deleteExtracted() {
        try {
            Path dir = that.getDataFolder().toPath().resolve("natives");
            if (Files.isDirectory(dir)) {
                try (var s = Files.list(dir)) {
                    for (Path p : s.toList()) {
                        Files.deleteIfExists(p);
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }
}
