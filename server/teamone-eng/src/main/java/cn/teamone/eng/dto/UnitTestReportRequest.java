package cn.teamone.eng.dto;

public record UnitTestReportRequest(
        String mrKey,
        String commitSha,
        boolean passed,
        int total,
        int failed,
        double coverageTotal,
        double coveragePatch,
        String reportUrl
) {}
