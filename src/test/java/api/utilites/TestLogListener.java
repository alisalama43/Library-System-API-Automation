
package api.utilites;

import io.qameta.allure.Allure;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;

import java.io.PrintWriter;
import java.io.StringWriter;

public class TestLogListener implements ITestListener {

    private static final Logger logger =
            LogManager.getLogger(TestLogListener.class);

    @Override
    public void onStart(ITestContext context) {
        logger.info("########## SUITE STARTED: {} ##########",
                context.getName());
    }

    @Override
    public void onTestStart(ITestResult result) {
        logger.info(">>> START TEST: {}.{}",
                result.getTestClass().getName(),
                result.getMethod().getMethodName());
    }

    @Override
    public void onTestSuccess(ITestResult result) {
        logger.info("<<< PASSED: {} ({} ms)",
                result.getMethod().getMethodName(),
                result.getEndMillis() - result.getStartMillis());
    }

    @Override
    public void onTestFailure(ITestResult result) {
        Throwable error = result.getThrowable();

        logger.error("<<< FAILED: {} | Reason: {}",
                result.getMethod().getMethodName(),
                error != null ? error.getMessage() : "Unknown Error",
                error);

        if (error != null) {
            StringWriter writer = new StringWriter();
            error.printStackTrace(new PrintWriter(writer));

            Allure.addAttachment(
                    "Failure Exception",
                    "text/plain",
                    writer.toString(),
                    ".txt"
            );
        }
    }

    @Override
    public void onTestSkipped(ITestResult result) {
        logger.warn("<<< SKIPPED: {}",
                result.getMethod().getMethodName());
    }

    @Override
    public void onFinish(ITestContext context) {
        logger.info(
                "########## SUITE FINISHED: {} | Passed: {}, Failed: {}, Skipped: {} ##########",
                context.getName(),
                context.getPassedTests().size(),
                context.getFailedTests().size(),
                context.getSkippedTests().size()
        );
    }
}
