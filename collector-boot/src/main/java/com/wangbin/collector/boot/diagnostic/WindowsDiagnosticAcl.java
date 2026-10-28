package com.wangbin.collector.boot.diagnostic;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinNT;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.AccessDeniedException;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 只读解析当前令牌和 NTFS 文件安全描述符；任何未知 ACE 类型均按不可信处理。 */
final class WindowsDiagnosticAcl {
    private static final String SERVICE_SID = "S-1-5-80-3842808028-2956155112-34687348-1206607495-337597549";
    private static final String SYSTEM_SID = "S-1-5-18";
    private static final String ADMIN_SID = "S-1-5-32-544";
    private static final int WRITE_RIGHTS = 0x00000016 | 0x00010000 | 0x00040000 | 0x00080000
            | 0x10000000 | 0x40000000;
    private static final Set<String> CONTROL_SIDS = Set.of(SYSTEM_SID, ADMIN_SID);

    private WindowsDiagnosticAcl() { }

    record Ace(String sid, String type, int mask, int flags) { }
    record FileEvidence(String status, String sampledAt, String ownerSid, boolean daclProtected,
                        List<Ace> aces, String expectedServiceAccess) { }
    record TokenEvidence(String userSid, List<String> groupSids) { }

    static TokenEvidence requireServiceToken() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) throw new IllegalStateException("仅限 Windows 服务");
        var token = new com.sun.security.auth.module.NTSystem();
        String userSid = token.getUserSID();
        if (!SERVICE_SID.equals(userSid)) throw new IllegalStateException("进程令牌不是目标服务账户");
        String[] groups = token.getGroupIDs();
        if (groups == null) throw new IllegalStateException("进程令牌组不可用");
        return new TokenEvidence(userSid, List.of(groups.clone()));
    }

    static void requireProtectedInstruction(Path path, boolean directory) {
        FileEvidence evidence = inspect(path);
        if (!"PRESENT".equals(evidence.status()) || !evidence.daclProtected()
                || (directory ? !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                              : !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                || !CONTROL_SIDS.contains(evidence.ownerSid())) {
            throw new IllegalStateException("授权指令的保护边界不可信");
        }
        boolean readableByService = false;
        for (Ace ace : evidence.aces()) {
            if (!"ALLOW".equals(ace.type()) || (ace.flags() & 0x10) != 0) {
                throw new IllegalStateException("授权指令包含未审核的 ACE");
            }
            if (!CONTROL_SIDS.contains(ace.sid()) && !SERVICE_SID.equals(ace.sid())) {
                throw new IllegalStateException("授权指令包含非授权身份");
            }
            if (SERVICE_SID.equals(ace.sid())) {
                if ((ace.mask() & WRITE_RIGHTS) != 0) throw new IllegalStateException("服务不应改写授权指令");
                readableByService |= (ace.mask() & 1) != 0 || (ace.mask() & 0x80000000) != 0;
            }
        }
        if (!readableByService) throw new IllegalStateException("服务不能读取授权指令");
    }

    static void requirePrivateOutput(Path path) {
        FileEvidence evidence = inspect(path);
        if (!"PRESENT".equals(evidence.status()) || !evidence.daclProtected()
                || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                || !Set.of(SYSTEM_SID, SERVICE_SID).contains(evidence.ownerSid())) {
            throw new IllegalStateException("诊断输出目录不受控");
        }
        boolean writable = false;
        for (Ace ace : evidence.aces()) {
            if (!"ALLOW".equals(ace.type())
                    || (!CONTROL_SIDS.contains(ace.sid()) && !SERVICE_SID.equals(ace.sid()))) {
                throw new IllegalStateException("诊断输出权限不受控");
            }
            if (SERVICE_SID.equals(ace.sid()) && (ace.mask() & WRITE_RIGHTS) != 0) writable = true;
        }
        if (!writable) throw new IllegalStateException("服务无诊断输出权限");
    }

    static FileEvidence inspect(Path path) {
        String at = Instant.now().toString();
        try {
            if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
                return new FileEvidence("ABSENT", at, null, false, List.of(), "NOT_SAMPLED");
            }
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()) throw new IllegalStateException("重解析路径不可信");
            WinNT.SECURITY_DESCRIPTOR_RELATIVE descriptor = Advapi32Util.getFileSecurityDescriptor(path.toFile(), false);
            if (descriptor.getOwner() == null || descriptor.getDiscretionaryACL() == null) {
                throw new IllegalStateException("文件 DACL 不完整");
            }
            List<Ace> entries = new ArrayList<>();
            for (WinNT.ACE_HEADER header : descriptor.getDiscretionaryACL().getACEs()) {
                if (!(header instanceof WinNT.ACCESS_ACEStructure access)) {
                    throw new IllegalStateException("不支持的 ACL 项");
                }
                String type = header.AceType == WinNT.ACCESS_ALLOWED_ACE_TYPE ? "ALLOW" : "OTHER";
                entries.add(new Ace(access.getSidString(), type, access.Mask, Byte.toUnsignedInt(header.AceFlags)));
            }
            return new FileEvidence("PRESENT", at, descriptor.getOwner().getSidString(),
                    (descriptor.Control & WinNT.SE_DACL_PROTECTED) != 0, List.copyOf(entries),
                    "ACE_ONLY_NOT_EFFECTIVE_ACCESS_PROOF");
        } catch (java.nio.file.NoSuchFileException exception) {
            return new FileEvidence("ABSENT", at, null, false, List.of(), "NOT_SAMPLED");
        } catch (AccessDeniedException exception) {
            return new FileEvidence("ACCESS_DENIED", at, null, false, List.of(), "NOT_SAMPLED");
        } catch (Exception exception) {
            return new FileEvidence("UNKNOWN", at, null, false, List.of(), "NOT_SAMPLED");
        }
    }

    static FileEvidence inspectDatabaseFile(Path path, boolean required) {
        FileEvidence evidence = inspect(path);
        if ("UNKNOWN".equals(evidence.status()) || "ACCESS_DENIED".equals(evidence.status())
                || (required && !"PRESENT".equals(evidence.status()))) {
            throw new IllegalStateException("SQLite 文件权限不可确认");
        }
        return evidence;
    }
}
