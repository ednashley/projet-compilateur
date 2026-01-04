import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class CodeOptimizerTest {
    public static void main(String[] args) throws Exception {

        // Test fonction facto
        Integer factoResult = testCode("test_facto");

        if(factoResult == null || factoResult != 120) throw new Exception("Le résultat du Facto(5) devrait être 120.");

        // Test du Spill
        Integer spillResult = testCode("test_spill");

        if(spillResult == null || spillResult != 496) throw new Exception("Le résultat du Spill devrait être 496.");


    }

    private static Integer testCode(String path) throws IOException, InterruptedException {

        // Récupère la gestion de la commande Python
        ProcessBuilder processBuilder = new ProcessBuilder("python", "projet_compil_etu\\Test\\simcode_test.py"); // Fichier test : le même que le simcode classique, mais avec les chemins modifiés
        processBuilder.redirectErrorStream(true);

        // Permet de rediriger la sortie et les erreurs du programme Python vers la console Java
        processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);

        // On récupère le code écrit dans le fichier test
        StringBuilder code = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader("projet_compil_etu\\Test\\" + path))) {
            String line;
            while ((line = br.readLine()) != null) {
                code.append(line);
            }
        } catch (IOException e) {
            System.out.println("Erreur lecture fichier : " + e.getMessage());
        }

        // On écrit le code dans le fichier input
        try (FileWriter fw = new FileWriter("projet_compil_etu\\input")) {
            fw.write(code.toString());
        } catch (IOException e) {
            System.out.println("Erreur écriture fichier : " + e.getMessage());
        }

        Main.main(null);

        // Exécute la commande Python et attend que le programme Python se termine
        Process process = processBuilder.start();
        int exitCode = process.waitFor();

        if(exitCode != 0) {
            System.err.println("Erreur : Le script Python a échoué.");
            return null;
        }

        // On récupère le résultat dans le fichier sorties.txt
        Integer result = null;
        try (BufferedReader br = new BufferedReader(new FileReader("projet_compil_etu\\sorties.txt"))) {
            String line;
            if((line = br.readLine()) != null){
                result = Integer.valueOf(line);
            }
        } catch (IOException e) {
            System.out.println("Erreur lecture fichier : " + e.getMessage());
        }

        return result;
    }
}
