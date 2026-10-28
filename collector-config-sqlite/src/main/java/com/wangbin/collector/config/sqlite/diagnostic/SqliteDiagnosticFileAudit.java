package com.wangbin.collector.config.sqlite.diagnostic;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinNT;

import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;

/** 诊断子进程中的只读 NTFS 文件安全描述符采样，不执行 ACL 修改。 */
public final class SqliteDiagnosticFileAudit {
    private SqliteDiagnosticFileAudit() {
    }

    public record Evidence(String status, String sampledAt, String ownerSid,
                           boolean daclProtected, int aceCount) {
    }

    public static Evidence inspect(Path path) {
        String sampledAt = Instant.now().toString();
        try {
            if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
                return new Evidence("ABSENT", sampledAt, null, false, 0);
            }
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()) {
                return new Evidence("UNKNOWN", sampledAt, null, false, 0);
            }
            WinNT.SECURITY_DESCRIPTOR_RELATIVE descriptor =
                    Advapi32Util.getFileSecurityDescriptor(path.toFile(), false);
            if (descriptor.getOwner() == null || descriptor.getDiscretionaryACL() == null) {
                return new Evidence("UNKNOWN", sampledAt, null, false, 0);
            }
            return new Evidence("PRESENT", sampledAt, descriptor.getOwner().getSidString(),
                    (descriptor.Control & WinNT.SE_DACL_PROTECTED) != 0,
                    descriptor.getDiscretionaryACL().getACEs().length);
        } catch (java.nio.file.NoSuchFileException exception) {
            return new Evidence("ABSENT", sampledAt, null, false, 0);
        } catch (AccessDeniedException exception) {
            return new Evidence("ACCESS_DENIED", sampledAt, null, false, 0);
        } catch (Exception exception) {
            return new Evidence("UNKNOWN", sampledAt, null, false, 0);
        }
    }
}
