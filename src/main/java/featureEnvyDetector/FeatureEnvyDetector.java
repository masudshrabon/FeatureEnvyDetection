package featureEnvyDetector;
//jre version 1.8 or later required
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class FeatureEnvyDetector {

    static class CodeElementExtractor extends VoidVisitorAdapter<Set<String>> {
        @Override
        public void visit(MethodCallExpr n, Set<String> collector) {
            collector.add("CALL_" + n.getNameAsString());
            super.visit(n, collector);
        }

        @Override
        public void visit(FieldAccessExpr n, Set<String> collector) {
            collector.add("FIELD_" + n.getNameAsString());
            super.visit(n, collector);
        }

        @Override
        public void visit(NameExpr n, Set<String> collector) {
            collector.add("FIELD_" + n.getNameAsString());
            super.visit(n, collector);
        }
    }

    static Map<String, Set<String>> classElements = new HashMap<>();
    static Map<String, Map<String, Set<String>>> methodElements = new HashMap<>();
    static Map<String, Integer> documentFrequency = new HashMap<>();
    static int totalDocuments = 0;

    public static void main(String[] args) throws Exception {
        Path root = Paths.get("E:\\Dataset\\IndustrialProjects\\IB Java Projects Dataset\\atadWord2Xml");
        List<File> javaFiles = Files.walk(root)
                .filter(p -> p.toString().endsWith(".java"))
                .map(Path::toFile)
                .collect(Collectors.toList());

        // Step 1: Parse all source files and build contexts
        for (File file : javaFiles) {
            CompilationUnit cu = StaticJavaParser.parse(file);
            for (TypeDeclaration<?> type : cu.getTypes()) {
                if (!(type instanceof ClassOrInterfaceDeclaration)) continue;

                String className = type.getNameAsString();
                Set<String> classContext = new HashSet<>();
                Map<String, Set<String>> methodContextMap = new HashMap<>();

                for (BodyDeclaration<?> member : type.getMembers()) {
                    if (member instanceof FieldDeclaration) {
                        FieldDeclaration fd = (FieldDeclaration) member;
                        fd.getVariables().forEach(var -> classContext.add("FIELD_" + var.getNameAsString()));
                    }

                    if (member instanceof MethodDeclaration) {
                        MethodDeclaration md = (MethodDeclaration) member;
                        Set<String> methodContext = new HashSet<>();
                        md.accept(new CodeElementExtractor(), methodContext);
                        methodContextMap.put(md.getNameAsString(), methodContext);
                        updateDocumentFrequency(methodContext);
                        totalDocuments++;
                    }
                }

                classElements.put(className, classContext);
                methodElements.put(className, methodContextMap);
                updateDocumentFrequency(classContext);
                totalDocuments++;
            }
        }

        // Step 2: CSV Output Setup
        try (PrintWriter writer = new PrintWriter(new FileWriter("feature_envy_results.csv"))) {
            writer.println("Method Class,Method Name,Current Class Similarity,Candidate Class,Candidate Similarity,Suggestion");

            for (String className : methodElements.keySet()) {
                for (String methodName : methodElements.get(className).keySet()) {
                    Set<String> mContext = methodElements.get(className).get(methodName);
                    double simWithOwnClass = cosineTFIDFSimilarity(mContext, classElements.get(className));

                    for (String otherClass : classElements.keySet()) {
                        if (otherClass.equals(className)) continue;

                        double simWithOther = cosineTFIDFSimilarity(mContext, classElements.get(otherClass));
                        double THRESHOLD = 0.1;
                        
                        if ((simWithOther - simWithOwnClass) > THRESHOLD) {
                            writer.printf("%s,%s,%.4f,%s,%.4f,YES%n",
                                    className, methodName, simWithOwnClass, otherClass, simWithOther);
                        }
                    }
                }
            }

            System.out.println("Results saved to feature_envy_results.csv");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    

    static void updateDocumentFrequency(Set<String> tokens) {
        Set<String> unique = new HashSet<>(tokens);
        for (String token : unique) {
            documentFrequency.put(token, documentFrequency.getOrDefault(token, 0) + 1);
        }
    }

    static Map<String, Double> tfidfVector(Set<String> context) {
        Map<String, Integer> tf = new HashMap<>();
        for (String token : context) {
            tf.put(token, tf.getOrDefault(token, 0) + 1);
        }

        Map<String, Double> tfidf = new HashMap<>();
        for (String token : tf.keySet()) {
            int df = documentFrequency.getOrDefault(token, 1);
            double idf = Math.log((double) totalDocuments / df);
            tfidf.put(token, tf.get(token) * idf);
        }
        return tfidf;
    }

    static double cosineTFIDFSimilarity(Set<String> a, Set<String> b) {
        Map<String, Double> vecA = tfidfVector(a);
        Map<String, Double> vecB = tfidfVector(b);

        Set<String> allTokens = new HashSet<>();
        allTokens.addAll(vecA.keySet());
        allTokens.addAll(vecB.keySet());

        double dot = 0.0, magA = 0.0, magB = 0.0;
        for (String token : allTokens) {
            double vA = vecA.getOrDefault(token, 0.0);
            double vB = vecB.getOrDefault(token, 0.0);
            dot += vA * vB;
            magA += vA * vA;
            magB += vB * vB;
        }

        return (magA == 0 || magB == 0) ? 0 : dot / (Math.sqrt(magA) * Math.sqrt(magB));
    }
}

