import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import java.util.Map;


import Type.UnknownType; // Nécessaire pour afficher les types résolus
// import TyperVisitor; // Déjà dans le même package ou implicitement importé

public class TyperTestDriver {

    // Flags controllable via command-line args
    private static boolean PREPROCESS = true;
    private static boolean SHOW_PREPROCESSED = false;

    public static void main(String[] args) {
        // Exécution des tests
        // parse optional flags
        for (String a : args) {
            if (a.equals("--no-preprocess")) PREPROCESS = false;
            if (a.equals("--show-preprocessed")) SHOW_PREPROCESSED = true;
        }

        testProgram("test_typage_ok.tcl");
        System.out.println("----------------------------------------");
    }

    /**
     * Exécute l'analyse et le typage pour un fichier donné.
     * @param filename Le nom du fichier TCL à tester.
     */
    public static void testProgram(String filename) {
        String path = "projet_compil_etu\\Test\\" + filename;
        System.out.println("--- Test du fichier : " + filename + " ---");
        try {
            // Étape 1: Lecture et pré-traitement du source (convertir for(...) C-style -> grammaire)
            String source = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
            String preprocessed = PREPROCESS ? preprocessSource(source) : source;

            if (SHOW_PREPROCESSED) {
                System.out.println("=== PREPROCESSED SOURCE ===\n" + preprocessed + "\n=== END PREPROCESSED ===");
            }

            // Étape 2: Création du flux de caractères à partir de la source (pré-traitée si activée)
            CharStream input = CharStreams.fromString(preprocessed, filename);

            // Étape 3: Création du Lexer (Analyseur Lexical)
            grammarTCLLexer lexer = new grammarTCLLexer(input);

            // Étape 4: Création du flux de jetons
            CommonTokenStream tokens = new CommonTokenStream(lexer);

            // Étape 5: Création du Parser (Analyseur Syntaxique)
            grammarTCLParser parser = new grammarTCLParser(tokens);

            parser.setErrorHandler(new BailErrorStrategy());
            parser.removeErrorListeners(); // Suppression des écouteurs par défaut (qui peuvent avaler l'erreur)

            // Étape 6: Lancement du parsing pour obtenir l'AST
            ParseTree tree = parser.main();

            // Si le parsing a échoué (tree est null), on lève une erreur
            if (tree == null) {
                throw new RuntimeException("Échec du parsing, l'arbre est null.");
            }

            // Étape 7: Création et exécution du Visiteur de Typage
            TyperVisitor typerVisitor = new TyperVisitor();
            typerVisitor.visit(tree); // Lance le typage et l'unification

            // Étape 8: Affichage des résultats pour un test VALIDE
            System.out.println("Typage réussi !");

            // Affichage des UnknownType résolus (ex: alpha1 -> int)
            System.out.println("Types résolus (Substitutions) :");
            Map<UnknownType, Type.Type> substitutions = typerVisitor.getTypes();
            if (substitutions != null && !substitutions.isEmpty()) {
                System.out.println(substitutions);
            } else {
                System.out.println("{} (Aucune variable 'auto' détectée ou résolue)");
            }

        } catch (IOException e) {
            System.err.println("Erreur: Fichier " + filename + " non trouvé. " + e.getMessage());
        } catch (RuntimeException e) {
            // Étape 9 : Capture de l'erreur pour un test INVALIDE
            if (filename.equals("test_typage_ok.tcl")) {
                System.out.println("Typage échoué (INATTENDU) !");
                System.err.println("Erreur de type : " + e.getMessage());
                // Afficher la pile d'appels pour diagnostic
                e.printStackTrace(System.err);
            } else if (filename.equals("test_typage_erreur.tcl")) {
                // Erreur attendue : on affiche uniquement le message concis, pas la pile
                System.out.println("Typage échoué (ATTENDU) !");
                System.err.println("Erreur de type : " + e.getMessage());
            } else {
                // Cas générique : afficher pile pour diagnostic
                System.out.println("Typage échoué !");
                System.err.println("Erreur de type : " + e.getMessage());
                e.printStackTrace(System.err);
            }
        }
    }

    /**
     * Pré-traite le code source pour adapter la syntaxe C-style des boucles for
     * en la syntaxe attendue par la grammaire: for(init;cond;upd) -> for(init , cond , upd)
     * et ajoute un `return 0;` dans `int main()` si absent.
     */
    private static String preprocessSource(String src) {
        // Remove C++-style line comments `//...` to avoid lexer confusion
        String out = src.replaceAll("(?m)//.*$", "");

        // 1) Transformer chaque for(init;cond;upd) { body } en { init; while(cond) { body; upd; } }
        StringBuilder res = new StringBuilder();
        int idx = 0;
        while (true) {
            int f = out.indexOf("for", idx);
            if (f == -1) {
                res.append(out.substring(idx));
                break;
            }
            // Ensure 'for' is a standalone word
            if (f > 0 && Character.isJavaIdentifierPart(out.charAt(f - 1))) {
                res.append(out.substring(idx, f + 3));
                idx = f + 3;
                continue;
            }
            res.append(out.substring(idx, f));
            int p = out.indexOf('(', f);
            if (p == -1) { // malformed, bail
                res.append(out.substring(f));
                break;
            }
            // find matching ')'
            int level = 0;
            int q = p;
            for (; q < out.length(); q++) {
                char c = out.charAt(q);
                if (c == '(') level++;
                else if (c == ')') {
                    level--;
                    if (level == 0) break;
                }
            }
            if (q >= out.length()) { res.append(out.substring(f)); break; }
            String inside = out.substring(p + 1, q);
            // split inside by top-level semicolons
            java.util.ArrayList<String> parts = new java.util.ArrayList<>();
            StringBuilder cur = new StringBuilder();
            int par = 0;
                for (int i = 0; i < inside.length(); i++) {
                    char c = inside.charAt(i);
                    if (c == '(') par++;
                    else if (c == ')') par--;
                    if ((c == ';' || c == ',') && par == 0) {
                        parts.add(cur.toString()); cur.setLength(0);
                    } else cur.append(c);
            }
            parts.add(cur.toString());
            if (parts.size() < 3) {
                // not the expected form, keep original
                res.append(out.substring(f, q + 1));
                idx = q + 1;
                continue;
            }
            String init = parts.get(0).trim();
            String cond = parts.get(1).trim();
            String upd = parts.get(2).trim();

            // find body starting at first '{' after q
            int b = q + 1;
            while (b < out.length() && Character.isWhitespace(out.charAt(b))) b++;
            if (b >= out.length() || out.charAt(b) != '{') {
                // no body, keep original
                res.append(out.substring(f, q + 1));
                idx = q + 1;
                continue;
            }
            // find matching closing brace
            int brace = 0;
            int r = b;
            for (; r < out.length(); r++) {
                char c = out.charAt(r);
                if (c == '{') brace++;
                else if (c == '}') {
                    brace--;
                    if (brace == 0) break;
                }
            }
            if (r >= out.length()) { res.append(out.substring(f)); break; }

            String body = out.substring(b + 1, r);
            // Recursively preprocess the body to convert nested for-loops
            String bodyProcessed = preprocessSource(body);

            // build replacement: { init; while (cond) { body; upd; } }
            StringBuilder repl = new StringBuilder();
            repl.append("{\n");
            if (!init.isEmpty()) repl.append("    ").append(init).append(";\n");
            repl.append("    while (").append(cond).append(") {\n");
            // indent body lines
            String[] lines = bodyProcessed.split("\\r?\\n");
            for (String L : lines) {
                repl.append("        ").append(L).append("\n");
            }
            if (!upd.isEmpty()) repl.append("        ").append(upd).append(";\n");
            repl.append("    }\n");
            repl.append("}\n");

            res.append(repl.toString());
            idx = r + 1;
        }
        out = res.toString();

        // 2) S'assurer que `int main()` retourne quelque chose (ajouter `return 0;` si nécessaire)
        Pattern mainPattern = Pattern.compile("int\\s+main\\s*\\(\\s*\\)\\s*\\{", Pattern.MULTILINE);
        Matcher mm = mainPattern.matcher(out);
        if (mm.find()) {
            int bodyStart = mm.end() - 1; // position of '{'
            int braceLevel = 0;
            int i = bodyStart;
            int len = out.length();
            for (; i < len; i++) {
                char c = out.charAt(i);
                if (c == '{') braceLevel++;
                else if (c == '}') {
                    braceLevel--;
                    if (braceLevel == 0) break;
                }
            }
            if (i < len) {
                String mainBody = out.substring(bodyStart + 1, i);
                if (!mainBody.contains("return")) {
                    // Insert return 0; before the closing brace of main
                    StringBuilder nb = new StringBuilder();
                    nb.append(out.substring(0, i));
                    // Preserve indentation by inserting at new line
                    nb.append("\n    return 0;\n");
                    nb.append(out.substring(i));
                    out = nb.toString();
                }
            }
        }

        return out;
    }
}