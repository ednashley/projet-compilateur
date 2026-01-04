import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;

public class CodeOptimizerTest {
    public static void main(String[] args) throws Exception {

        // Test fonction facto
        ArrayList<ArrayList<Integer>> factoResult = testCode("test_facto");

        if(factoResult == null || factoResult.getFirst().getFirst() != 120) throw new Exception("Le résultat du Facto(5) devrait être 120.");

        // Test fonction fibo
        ArrayList<ArrayList<Integer>> fiboResult = testCode("test_fibo");

        int[] tab1 = {0, 1, 1, 2, 3, 5, 8, 13, 21, 34};
        ArrayList<Integer> expectingTab1 = getArrayList(tab1);

        boolean areEqual = fiboResult != null && fiboResult.size() == expectingTab1.size();
        if(areEqual) {
            for(int i = 0; i < tab1.length; i++){
                if(!fiboResult.get(i).getFirst().equals(expectingTab1.get(i))){
                    areEqual = false;
                }
            }
        }

        if(!areEqual) throw new Exception("Les valeurs du tableaux sont incorrects.");

        // Test des tableaux
        ArrayList<ArrayList<Integer>> tabResult = testCode("test_tab");

        int[] tab2 = {100, 25, 30, 15, 200};
        ArrayList<Integer> expectingTab2 = getArrayList(tab2);
        if(tabResult == null || tabResult.getFirst().getFirst() != 100 || tabResult.get(1).getFirst() != 30 || !tabResult.getLast().equals(expectingTab2)) throw new Exception("Les valeurs de retour sont incorrects.");

        // Test du Spill
        ArrayList<ArrayList<Integer>> spillResult = testCode("test_spill");

        if(spillResult == null || spillResult.getFirst().getFirst() != 496) throw new Exception("Le résultat du Spill devrait être 496.");
    }

    private static ArrayList<ArrayList<Integer>> testCode(String path) throws IOException, InterruptedException {

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
        ArrayList<ArrayList<Integer>> result = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader("projet_compil_etu\\sorties.txt"))) {
            String line;
            while((line = br.readLine()) != null){
                result.add(new ArrayList<>());
                for(String n : line.split(" ")) {
                    result.getLast().add(Integer.valueOf(n));
                }
            }
        } catch (IOException e) {
            System.out.println("Erreur lecture fichier : " + e.getMessage());
        }

        for (ArrayList<Integer> integers : result) {
            System.out.println(integers);
        }

        System.out.println();

        return result;
    }

    private static ArrayList<Integer> getArrayList(int[] tab){

        ArrayList<Integer> list = new ArrayList<>();
        for(int i : tab){
            list.add(i);
        }

        return list;
    }
}
