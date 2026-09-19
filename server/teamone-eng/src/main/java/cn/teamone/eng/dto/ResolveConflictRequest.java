package cn.teamone.eng.dto;

public record ResolveConflictRequest(
        String filePath,
        String solution
) {}
