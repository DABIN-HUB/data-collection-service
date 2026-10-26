package com.wangbin.collector.config.sqlite.database;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** 只约束专用数据库父目录及库文件，不修改共享祖先目录。 */
final class SqliteFileSecurity {
    private static final Set<PosixFilePermission> DIRECTORY_MODE = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_MODE = PosixFilePermissions.fromString("rw-------");
    private SqliteFileSecurity() { }

    static Path prepare(Path input, boolean create) throws IOException {
        if (input == null) throw new IllegalArgumentException("数据库路径不能为空");
        Path path = input.toAbsolutePath().normalize();
        Path parent = path.getParent();
        if (parent == null || parent.getParent() == null || shared(parent)) {
            throw new IllegalStateException("数据库必须位于专用目录，禁止直接使用共享目录");
        }
        rejectLinks(path);
        if (!create && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("已有数据库不存在或不是常规文件");
        }
        if (create && Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("新建数据库目标已经存在");
        }
        if (create) {
            for (String suffix : List.of("-wal", "-shm", "-journal")) {
                if (Files.exists(Path.of(path + suffix), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException("数据库附属文件已存在，不能视为全新安装");
                }
            }
        }
        // 必须先判断只读状态，不能通过收紧权限把既有只读文件变成可写。
        if (!create) requireWritable(path);
        if (!create) {
            for (String suffix : List.of("-wal", "-shm", "-journal")) {
                inspectDatabaseFile(Path.of(path + suffix), false, true);
            }
        }
        if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
            if (!create) throw new IllegalStateException("数据库专用目录不存在");
            createPrivateDirectories(parent);
        }
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("数据库父路径不是目录");
        }
        requireWritable(parent);
        // 明确策略：将调用者选定的专用父目录收紧为所有者私有，不触碰任何既有祖先。
        restrict(parent, true);
        if (!create) restrict(path, false);
        if (!create) secureSidecars(path);
        return path;
    }

    /** 同级保护目录比数据库及其父目录活得更久；两者任一已存在均不推断首次安装。 */
    static void claimNewInstallationDirectory(Path input) throws IOException {
        if (input == null) throw new IllegalArgumentException("数据库路径不能为空");
        Path path = input.toAbsolutePath().normalize();
        Path parent = path.getParent();
        if (parent == null || parent.getParent() == null || shared(parent)) {
            throw new IllegalStateException("数据库必须位于专用目录，禁止直接使用共享目录");
        }
        Path guard = parent.resolveSibling(parent.getFileName() + ".wangbin-installation");
        rejectLinks(path);
        rejectLinks(guard);
        if (!Files.notExists(parent, LinkOption.NOFOLLOW_LINKS)
                || !Files.notExists(guard, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("已存在安装目录或安装保护但数据库缺失，禁止自动创建空库");
        }
        for (String suffix : List.of("-wal", "-shm", "-journal")) {
            if (!Files.notExists(Path.of(path + suffix), LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("数据库附属文件状态异常，禁止自动初始化");
            }
        }
        createPrivateDirectories(parent.getParent());
        // 排他创建保护先于数据目录；并发启动或上次安装中断只能失败，不能覆盖。
        createPrivateDirectory(guard);
        createPrivateDirectory(parent);
    }

    /** 已验证的已有库补齐独立保护证据，兼容早期显式初始化的安装。 */
    static void ensureInstallationGuard(Path input) throws IOException {
        Path path = input.toAbsolutePath().normalize();
        Path parent = path.getParent();
        if (parent == null || parent.getParent() == null || shared(parent)) {
            throw new IllegalStateException("数据库必须位于专用目录，禁止直接使用共享目录");
        }
        Path guard = parent.resolveSibling(parent.getFileName() + ".wangbin-installation");
        rejectLinks(path);
        rejectLinks(guard);
        requireWritable(parent);
        if (Files.notExists(guard, LinkOption.NOFOLLOW_LINKS)) {
            try {
                createPrivateDirectory(guard);
            } catch (FileAlreadyExistsException exception) {
                // 另一个合法启动者可同时补齐保护，但不能接受未知所有者或非目录。
            }
        }
        if (!Files.isDirectory(guard, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("安装保护不可确认，禁止继续启动");
        }
        requireWritable(guard);
        restrict(guard, true);
    }

    private static boolean shared(Path parent) {
        List<String> sharedPaths = new ArrayList<>(List.of(System.getProperty("user.home"),
                System.getProperty("user.dir"), System.getProperty("java.io.tmpdir")));
        sharedPaths.add(Path.of(System.getProperty("user.home")).toAbsolutePath().getParent().toString());
        for (String name : List.of("ProgramData", "PUBLIC", "SystemRoot", "ProgramFiles", "TEMP", "TMP")) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) sharedPaths.add(value);
        }
        for (String value : sharedPaths) {
            if (parent.equals(Path.of(value).toAbsolutePath().normalize())) return true;
        }
        String normalized = parent.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        return Set.of("/var", "/var/lib", "/tmp", "/home", "/users", "/usr", "/etc").contains(normalized);
    }

    private static void rejectLinks(Path path) throws IOException {
        for (Path cursor = path; cursor != null; cursor = cursor.getParent()) {
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes attributes = Files.readAttributes(cursor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) {
                    throw new IllegalStateException("数据库路径不允许符号链接或重解析路径");
                }
            }
        }
    }

    private static void createPrivateDirectories(Path directory) throws IOException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        createPrivateDirectories(directory.getParent());
        createPrivateDirectory(directory);
    }

    private static void createPrivateDirectory(Path directory) throws IOException {
        if (Files.getFileStore(directory.getParent()).supportsFileAttributeView(PosixFileAttributeView.class)) {
            Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(DIRECTORY_MODE));
        } else {
            Files.createDirectory(directory);
        }
        restrict(directory, true);
    }

    static void requireWritable(Path path) throws IOException {
        requireOwner(path, trustedServiceOwner(path));
        DosFileAttributeView dos = Files.getFileAttributeView(path, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (dos != null && dos.readAttributes().isReadOnly()) throw new IllegalStateException("数据库文件或目录为只读");
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null && !posix.readAttributes().permissions().contains(PosixFilePermission.OWNER_WRITE)) {
            throw new IllegalStateException("数据库文件或目录缺少所有者写权限");
        }
        if (!Files.isReadable(path) || !Files.isWritable(path)) throw new IllegalStateException("数据库路径不可读写");
    }

    static void restrict(Path path, boolean directory) throws IOException {
        requireOwner(path, trustedServiceOwner(path));
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            Set<PosixFilePermission> expected = directory ? DIRECTORY_MODE : FILE_MODE;
            posix.setPermissions(expected);
            if (!posix.readAttributes().permissions().equals(expected)) throw new IllegalStateException("数据库 POSIX 权限校验失败");
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) throw new IllegalStateException("文件系统不支持受控数据库权限");
        UserPrincipal owner = acl.getOwner();
        AclEntry.Builder entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) entry.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        acl.setAcl(List.of(entry.build()));
        List<AclEntry> actual = acl.getAcl();
        if (actual.isEmpty() || actual.stream().anyMatch(item -> item.type() == AclEntryType.ALLOW && !item.principal().equals(owner))) {
            throw new IllegalStateException("数据库 ACL 权限校验失败");
        }
    }

    static void secureSidecars(Path path) throws IOException {
        for (String suffix : List.of("", "-wal", "-shm", "-journal")) {
            inspectDatabaseFile(Path.of(path + suffix), true, !suffix.isEmpty());
        }
    }

    /** 在 JDBC 打开文件之前检查既有附属文件，不能等连接触碰 WAL 后才检查所有权。 */
    static void requireTrustedFiles(Path path) throws IOException {
        requireWritable(path.getParent());
        for (String suffix : List.of("", "-wal", "-shm", "-journal")) {
            inspectDatabaseFile(Path.of(path + suffix), false, !suffix.isEmpty());
        }
    }

    private static void inspectDatabaseFile(Path file, boolean tighten, boolean optional) throws IOException {
        if (optional && Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("数据库附属路径不是常规文件");
            }
            requireWritable(file);
            if (tighten) restrict(file, false);
        } catch (IOException | IllegalStateException exception) {
            // 最后一个并发连接关闭时 SQLite 可以删除 WAL/SHM；仅确认已消失的可选文件可以跳过。
            // notExists 不把“无权判断是否存在”当成缺失；主库缺失或仍存在的权限/所有权错误必须失败。
            if (!optional || !Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) throw exception;
        }
    }

    /** 身份来自操作系统认证模块，不信任可由 -D 伪造的 user.name，不静默接管其他所有者文件。 */
    private static UserPrincipal trustedServiceOwner(Path path) throws IOException {
        try {
            var lookup = path.getFileSystem().getUserPrincipalLookupService();
            if (path.getFileSystem().supportedFileAttributeViews().contains("unix")) {
                long uid = new com.sun.security.auth.module.UnixSystem().getUid();
                return lookup.lookupPrincipalByName(Long.toString(uid));
            }
            if (path.getFileSystem().supportedFileAttributeViews().contains("acl")) {
                var identity = new com.sun.security.auth.module.NTSystem();
                String domain = identity.getDomain();
                String account = domain == null || domain.isBlank() ? identity.getName() : domain + "\\" + identity.getName();
                return lookup.lookupPrincipalByName(account);
            }
            throw new IllegalStateException("无法验证数据库服务所有者身份");
        } catch (LinkageError | SecurityException exception) {
            throw new IllegalStateException("数据库服务身份模块不可用，拒绝访问");
        }
    }

    static void requireOwner(Path path, UserPrincipal expected) throws IOException {
        if (expected == null || !Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).equals(expected)) {
            throw new IllegalStateException("数据库所有者与可信服务身份不一致，拒绝访问");
        }
    }
}
