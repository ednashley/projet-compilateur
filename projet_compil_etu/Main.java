

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import Asm.Program;

import java.io.*;

public class Main {
	public static void main(String[] args) {
		// Lire le fichier input.txt
		String fichier = "projet_compil_etu\\input";
		StringBuilder input = new StringBuilder();

		try (BufferedReader br = new BufferedReader(new FileReader(fichier))) {
			String ligne;
			while ((ligne = br.readLine()) != null) {
				input.append(ligne).append("\n");
			}
		} catch (Exception e) {
			System.out.println("Erreur lecture fichier : " + e.getMessage());
			return;
		}

		// Analyse lexicale et syntaxique
		grammarTCLLexer lexer = new grammarTCLLexer(CharStreams.fromString(input.toString()));
		CommonTokenStream tokens = new CommonTokenStream(lexer);
		grammarTCLParser parser = new grammarTCLParser(tokens);
		grammarTCLParser.MainContext tree = parser.main();

		// TyperVisitor
		TyperVisitor typer = new TyperVisitor();
		typer.visit(tree);

		// Génération de code
		CodeGenerator codeGen = new CodeGenerator (typer.getTypes(), typer.getEnvironment());
		Program linearProgram = codeGen.visit(tree);

        // Optimisation de code
        CodeOptimizer codeOpt = new CodeOptimizer(32);
        Program program = codeOpt.optimize(linearProgram);

		// Affichage du code linéaire
		System.out.println("=== CODE LINÉAIRE ===");
		System.out.println(linearProgram);

        System.out.println();
        System.out.println();
        System.out.println();

        // Affichage du code final
        System.out.println("=== CODE FINAL ===");
        System.out.println(program);

		//  Écrire dans un fichier le programme linéaire
		try (FileWriter fw = new FileWriter("projet_compil_etu\\prog_lineaire.asm")) {
			fw.write(linearProgram.toString());
		} catch (IOException e) {
			System.out.println("Erreur écriture fichier : " + e.getMessage());
		}

        //  Écrire dans un fichier le programme final
        try (FileWriter fw = new FileWriter("projet_compil_etu\\prog.asm")) {
            fw.write(program.toString());
        } catch (IOException e) {
            System.out.println("Erreur écriture fichier : " + e.getMessage());
        }
	}
}
