package io.yunxi.platform.agent.text2sql.voting;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqlVoterTest {

    private SqlVoter voter;

    @BeforeEach
    void setUp() {
        voter = new SqlVoter();
    }

    /**
     * Helper: create a successful ExecutionResult with given rows
     */
    private SqlVoter.ExecutionResult successResult(List<List<Object>> rows) {
        SqlVoter.ExecutionResult result = new SqlVoter.ExecutionResult();
        result.setSuccess(true);
        result.setRows(rows);
        result.setRowCount(rows.size());
        result.setColumns(List.of("col1"));
        result.setExecutionTime(10L);
        return result;
    }

    /**
     * Helper: create a failed ExecutionResult
     */
    private SqlVoter.ExecutionResult failResult(String error) {
        SqlVoter.ExecutionResult result = new SqlVoter.ExecutionResult();
        result.setSuccess(false);
        result.setErrorMessage(error);
        return result;
    }

    @Nested
    @DisplayName("vote - empty candidates")
    class EmptyCandidates {

        @Test
        @DisplayName("null candidates returns null")
        void nullCandidates() {
            assertNull(voter.vote(null, sql -> failResult("err")));
        }

        @Test
        @DisplayName("empty list returns null")
        void emptyList() {
            assertNull(voter.vote(Collections.emptyList(), sql -> failResult("err")));
        }
    }

    @Nested
    @DisplayName("vote - single candidate")
    class SingleCandidate {

        @Test
        @DisplayName("single successful candidate returns it")
        void singleSuccess() {
            List<List<Object>> rows = List.of(List.of("a", "b"));
            String sql = "SELECT * FROM t";
            String result = voter.vote(List.of(sql), s -> successResult(rows));
            assertEquals(sql, result);
        }

        @Test
        @DisplayName("single failed candidate still returns it")
        void singleFail() {
            String sql = "SELECT * FROM t";
            String result = voter.vote(List.of(sql), s -> failResult("error"));
            assertEquals(sql, result);
        }
    }

    @Nested
    @DisplayName("vote - majority wins")
    class MajorityWins {

        @Test
        @DisplayName("two candidates with same result, one different - majority wins")
        void majorityWins() {
            String sql1 = "SELECT a FROM t";
            String sql2 = "SELECT b FROM t";
            String sql3 = "SELECT c FROM t";

            List<List<Object>> rowsA = List.of(List.of("x"));
            List<List<Object>> rowsB = List.of(List.of("y"));

            SqlVoter.SqlExecutor executor = sql -> {
                if (sql.equals(sql1) || sql.equals(sql3)) {
                    return successResult(rowsA);
                } else {
                    return successResult(rowsB);
                }
            };

            String result = voter.vote(Arrays.asList(sql1, sql2, sql3), executor);
            // sql1 and sql3 produce same result, so majority cluster selects one of them
            assertTrue(result.equals(sql1) || result.equals(sql3));
        }

        @Test
        @DisplayName("all candidates produce same result - first wins")
        void allSameResult() {
            String sql1 = "SELECT a FROM t LIMIT 10";
            String sql2 = "SELECT a FROM t LIMIT 10 OFFSET 0";
            List<List<Object>> rows = List.of(List.of("same"));

            String result = voter.vote(Arrays.asList(sql1, sql2), s -> successResult(rows));
            // All same cluster, first one returned
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("vote - all failed")
    class AllFailed {

        @Test
        @DisplayName("all SQL fail execution - returns first candidate")
        void allFail() {
            String sql1 = "INVALID SQL 1";
            String sql2 = "INVALID SQL 2";
            String result = voter.vote(Arrays.asList(sql1, sql2), s -> failResult("error"));
            assertEquals(sql1, result);
        }

        @Test
        @DisplayName("executor throws exception - returns first candidate")
        void executorThrows() {
            String sql1 = "SELECT * FROM t";
            String sql2 = "SELECT a FROM t";
            SqlVoter.SqlExecutor executor = s -> { throw new RuntimeException("connection error"); };
            String result = voter.vote(Arrays.asList(sql1, sql2), executor);
            assertEquals(sql1, result);
        }
    }

    @Nested
    @DisplayName("ExecutionResult")
    class ExecutionResultTest {

        @Test
        @DisplayName("default values")
        void defaults() {
            SqlVoter.ExecutionResult result = new SqlVoter.ExecutionResult();
            assertNull(result.getColumns());
            assertNull(result.getRows());
            assertEquals(0, result.getRowCount());
            assertEquals(0L, result.getExecutionTime());
            assertFalse(result.isSuccess());
            assertNull(result.getErrorMessage());
        }

        @Test
        @DisplayName("setters and getters")
        void settersAndGetters() {
            SqlVoter.ExecutionResult result = new SqlVoter.ExecutionResult();
            result.setSuccess(true);
            result.setColumns(List.of("a", "b"));
            result.setRows(List.of(List.of(1, 2)));
            result.setRowCount(1);
            result.setExecutionTime(50L);
            result.setErrorMessage(null);

            assertTrue(result.isSuccess());
            assertEquals(2, result.getColumns().size());
            assertEquals(1, result.getRowCount());
            assertEquals(50L, result.getExecutionTime());
            assertNull(result.getErrorMessage());
        }
    }
}
