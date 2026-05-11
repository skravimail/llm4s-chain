package org.llm4s.template.parallel_workflow;

/**
 * Plain POJO so langchain4j's default Jackson codec can deserialize the model's
 * JSON response without needing jackson-module-scala on the classpath.
 */
public class CvReview {
    public int score;
    public String feedback;

    public CvReview() {}

    @Override
    public String toString() {
        return "CvReview{score=" + score + ", feedback='" + feedback + "'}";
    }
}
