
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

import Asm.*;

import org.antlr.v4.runtime.tree.AbstractParseTreeVisitor;
import Type.PrimitiveType;
import Type.Type;
import Type.UnknownType;
import Type.ArrayType;




public class CodeGenerator  extends AbstractParseTreeVisitor<Program> implements grammarTCLVisitor<Program> {

    private Map<UnknownType,Type> types;
    private Map<String, Type> varTypeMap;

    private int regCount; //compteur de registres
    private int labelCount = 0; //compteur de label
    private final int SP = 2; //  stackPointeur pile
    private final int TP = 1; // heap / tableaux
    private Stack<Map<String, Integer>> scopeStack = new Stack<>(); // une pile de dictionnaires
    private int startReg = 3; // registre de départ de la fonction (permet d'avoir tous les registres d'une fonction)



    /**
     * Constructeur
     * @param types types de chaque variable du code source
     */
    public CodeGenerator(Map<UnknownType, Type> types, Map<String, Type> varTypeMap) {
        this.types = types;
        this.varTypeMap = varTypeMap;
        this.regCount = 3;
    }


    /**
     * Entre dans un nouveau scope (bloc, fonction, boucle for)
     */
    private void enterScope() {
        scopeStack.push(new HashMap<>());
    }

    /**
     * Sort du scope actuel
     */
    private void exitScope() {
        if (!scopeStack.isEmpty()) {
            scopeStack.pop();
        }
    }

    /**
     * Déclare une variable dans le scope actuel
     * @throws RuntimeException si la variable existe déjà dans ce scope
     */
    private void declareVar(String name, int register) {
        if (scopeStack.isEmpty()) {
            throw new RuntimeException("Aucun scope actif");
        }
        if (scopeStack.peek().containsKey(name)) {
            throw new RuntimeException("Variable déjà déclarée dans ce scope : " + name);
        }
        scopeStack.peek().put(name, register);
    }

    /**
     * Récupère le registre d'une variable en cherchant dans tous les scopes
     * @throws RuntimeException si la variable n'existe pas
     */
    private Integer getVar(String name) {
        // Chercher du scope le plus récent au plus ancien
        for (int i = scopeStack.size() - 1; i >= 0; i--) {
            if (scopeStack.get(i).containsKey(name)) {
                return scopeStack.get(i).get(name);
            }
        }
        throw new RuntimeException("Variable non déclarée : " + name);
    }

    private Type getVarType(String varName) {
        Type t = varTypeMap.get(varName);
        if (t == null) {
            // Fallback : type inconnu, supposer INT
            return new PrimitiveType(Type.Base.INT);
        }
        //  Appliquer les substitutions
        return t.substituteAll(types);
    }



    private String newLabel(String prefix) {
        labelCount++;
        return prefix + "_" + labelCount;
    }

    public int newRegister() {
        return regCount++;
    }


    private Program setRegisterTo(int register, int value) {
        Program program = new Program();
        program.addInstruction(new UAL(UAL.Op.XOR, register, register, register));
        program.addInstruction(new UALi(UALi.Op.ADD, register, register, value));
        return program;
    }


    @Override
    public Program visitOpposite(grammarTCLParser.OppositeContext ctx) {
        // on visite l'expression a l'interieur
        Program pExpr = visit(ctx.expr());
        Program p = new Program();
        p.addInstructions(pExpr);

        int exprReg = regCount - 1;

        // Charger 0 dans un registre
        int rZero = newRegister();
        p.addInstruction(new UALi(UALi.Op.ADD, rZero, 0, 0));

        // Registre pour le résultat
        int resultReg = newRegister();
        p.addInstruction(new UAL(UAL.Op.SUB, resultReg, rZero, exprReg)); //  Résultat = 0 - exprReg

        return p;
    }


    @Override
    public Program visitNegation(grammarTCLParser.NegationContext ctx) {
        Program program = new Program();

        // Évaluer l'expression à l'intérieur du !
        Program exprProgram = visit(ctx.expr());
        program.addInstructions(exprProgram);

        // Le registre contenant le résultat de l'expression
        int exprReg = regCount - 1;

        // Créer un nouveau registre pour le résultat de la négation
        int resultReg = newRegister();

        // XOR avec 1 pour inverser 0 → 1 ou 1 → 0
        program.addInstruction(new UALi(UALi.Op.XOR, resultReg, exprReg, 1));

        return program;
    }


    @Override
    public Program visitComparison(grammarTCLParser.ComparisonContext ctx) {
        Program program = new Program();

        // Labels
        String trueLabel = newLabel("true");
        String endLabel  = newLabel("end");

        // ✓ Évaluer expr gauche AVANT de créer resultReg
        Program leftProg = visit(ctx.expr(0));
        program.addInstructions(leftProg);
        int leftReg = regCount - 1;

        // ✓ Évaluer expr droite
        Program rightProg = visit(ctx.expr(1));
        program.addInstructions(rightProg);
        int rightReg = regCount - 1;

        // ✓ Maintenant créer le registre résultat
        int resultReg = newRegister();

        // Test de comparaison
        switch (ctx.getChild(1).getText()) {
            case ">" ->
                    program.addInstruction(new CondJump(CondJump.Op.JSUP, leftReg, rightReg, trueLabel));
            case "<" ->
                    program.addInstruction(new CondJump(CondJump.Op.JINF, leftReg, rightReg, trueLabel));
            case ">=" ->
                    program.addInstruction(new CondJump(CondJump.Op.JSEQ, leftReg, rightReg, trueLabel));
            case "<=" ->
                    program.addInstruction(new CondJump(CondJump.Op.JIEQ, leftReg, rightReg, trueLabel));
        }

        // Cas FAUX → result = 0
        program.addInstruction(new UAL(UAL.Op.XOR, resultReg, resultReg, resultReg));
        program.addInstruction(new JumpCall(JumpCall.Op.JMP, endLabel));

        // Cas VRAI → result = 1
        Program trueProg = new Program();
        trueProg.addInstruction(new UAL(UAL.Op.XOR, resultReg, resultReg, resultReg));
        trueProg.addInstruction(new UALi(UALi.Op.ADD, resultReg, resultReg, 1));
        trueProg.getInstructions().getFirst().setLabel(trueLabel);
        program.addInstructions(trueProg);

        // Fin
        Program endProg = new Program();
        endProg.addInstruction(new UALi(UALi.Op.ADD, resultReg, resultReg, 0));
        endProg.getInstructions().getFirst().setLabel(endLabel);
        program.addInstructions(endProg);

        return program;
    }
    @Override
    public Program visitOr(grammarTCLParser.OrContext ctx) {
        // expr || expr

        Program program = new Program();

        // Évaluer l'expression gauche
        program.addInstructions(visit(ctx.expr(0)));
        int leftReg = regCount - 1;

        // Évaluer l'expression droite
        program.addInstructions(visit(ctx.expr(1)));
        int rightReg = regCount - 1;

        // Registre pour le résultat
        int resultReg = newRegister();

        // OR logique (valeurs garanties 0 ou 1)
        program.addInstruction(
                new UAL(UAL.Op.OR, resultReg, leftReg, rightReg)
        );

        return program;
    }


    @Override
    public Program visitAnd(grammarTCLParser.AndContext ctx) {
        // expr && expr

        Program program = new Program();

        // Évaluer l'expression gauche
        program.addInstructions(visit(ctx.expr(0)));
        int leftReg = regCount - 1;

        // Évaluer l'expression droite
        program.addInstructions(visit(ctx.expr(1)));
        int rightReg = regCount - 1;

        // Créer un registre pour le résultat
        int resultReg = newRegister();

        // AND logique (valeurs garanties 0 ou 1)
        program.addInstruction(
                new UAL(UAL.Op.AND, resultReg, leftReg, rightReg)
        );

        return program;
    }



    @Override
    public Program visitMultiplication(grammarTCLParser.MultiplicationContext ctx) {
        Program program = new Program();

        // Évaluer l'expression gauche
        Program leftExprProgram = visit(ctx.expr(0));
        program.addInstructions(leftExprProgram);
        int leftReg = regCount - 1; // registre contenant le résultat de l'expression gauche

        // Évaluer l'expression droite
        Program rightExprProgram = visit(ctx.expr(1));
        program.addInstructions(rightExprProgram);
        int rightReg = regCount - 1; // registre contenant le résultat de l'expression droite

        // Créer un nouveau registre pour stocker le résultat
        int resultReg = newRegister();

        // Générer le code pour l'opération (*, /, %)
        switch(ctx.op.getText()) {
            case "*" -> program.addInstruction(new UAL(UAL.Op.MUL, resultReg, leftReg, rightReg));
            case "/" -> program.addInstruction(new UAL(UAL.Op.DIV, resultReg, leftReg, rightReg));
            case "%" -> program.addInstruction(new UAL(UAL.Op.MOD, resultReg, leftReg, rightReg));
            default -> throw new RuntimeException("Opérateur inconnu : " + ctx.op.getText());
        }

        return program;
    }

    @Override
    public Program visitEquality(grammarTCLParser.EqualityContext ctx) {
        Program program = new Program();

        String trueLabel = newLabel("eq_true");
        String endLabel  = newLabel("eq_end");

        // Évaluer les deux expressions
        program.addInstructions(visit(ctx.expr(0)));
        int leftReg = regCount - 1;

        program.addInstructions(visit(ctx.expr(1)));
        int rightReg = regCount - 1;

        int resultReg = newRegister();

        // Test
        switch (ctx.getChild(1).getText()) {
            case "==" ->
                    program.addInstruction(
                            new CondJump(CondJump.Op.JEQU, leftReg, rightReg, trueLabel)
                    );
            case "!=" ->
                    program.addInstruction(
                            new CondJump(CondJump.Op.JNEQ, leftReg, rightReg, trueLabel)
                    );
        }

        // Cas FAUX
        program.addInstruction(new UAL(UAL.Op.XOR, resultReg, resultReg, resultReg));
        program.addInstruction(new JumpCall(JumpCall.Op.JMP, endLabel));

        // Cas VRAI
        program.addInstruction(new UAL(UAL.Op.XOR, resultReg, resultReg, resultReg));
        program.getInstructions().getLast().setLabel(trueLabel);

        program.addInstruction(new UALi(UALi.Op.ADD, resultReg, resultReg, 1));

        // FIN
        program.addInstruction(new UALi(UALi.Op.ADD, resultReg, resultReg, 0));
        program.getInstructions().getLast().setLabel(endLabel);

        return program;
    }
    @Override
    public Program visitAddition(grammarTCLParser.AdditionContext ctx) {
        // expr + expr | expr - expr

        Program program = new Program();

        // Évaluer l'expression gauche
        program.addInstructions(visit(ctx.expr(0)));
        int leftReg = regCount - 1;

        // Évaluer l'expression droite
        program.addInstructions(visit(ctx.expr(1)));
        int rightReg = regCount - 1;

        // Registre destination
        int resultReg = newRegister();

        switch (ctx.getChild(1).getText()) {
            case "+" ->
                    program.addInstruction(new UAL(UAL.Op.ADD, resultReg, leftReg, rightReg));
            case "-" ->
                    program.addInstruction(new UAL(UAL.Op.SUB, resultReg, leftReg, rightReg));
        }

        return program;
    }




    @Override
    public Program visitInteger(grammarTCLParser.IntegerContext ctx) {
        Program program = new Program();

        // Créer un nouveau registre pour stocker l'entier
        int reg = newRegister();

        // Charger la valeur entière dans le registre
        int value = Integer.parseInt(ctx.INT().getText());
        program.addInstruction(new UALi(UALi.Op.ADD, reg, 0, value));

        return program;
    }



    @Override
    public Program visitBoolean(grammarTCLParser.BooleanContext ctx) {
        Program program = new Program();

        // Créer un nouveau registre pour stocker le booléen
        int reg = newRegister();

        // Initialiser à 0 (false)
        program.addInstruction(new UAL(UAL.Op.XOR, reg, reg, reg));

        // Si c'est true, mettre 1 dans le registre
        if (ctx.BOOL().getText().equals("true")) {
            program.addInstruction(new UALi(UALi.Op.ADD, reg, reg, 1));
        }

        return program;
    }

    @Override
    public Program visitVariable(grammarTCLParser.VariableContext ctx) {
        Program program = new Program();

        String varName = ctx.VAR().getText();
        int varReg = getVar(varName);

        // Créer un nouveau registre pour cette utilisation
        int resultReg = newRegister();

        // Copier la valeur
        program.addInstruction(new UALi(UALi.Op.ADD, resultReg, varReg, 0));

        return program;
    }

    @Override
    public Program visitPrint(grammarTCLParser.PrintContext ctx) {
        // PRINT '(' VAR ')' SEMICOL
        Program program = new Program();

        String varName = ctx.VAR().getText();
        Integer varReg = getVar(varName);

        //  Utiliser getVarType() au lieu de boucler
        Type varType = getVarType(varName);

        if (varType instanceof ArrayType) {
            program.addInstructions(printArray(varReg, varType));
        } else {
            program.addInstruction(new IO(IO.Op.PRINT, varReg));
        }

        // Nouvelle ligne
        int newLineReg = newRegister();
        program.addInstructions(setRegisterTo(newLineReg, 10));
        program.addInstruction(new IO(IO.Op.OUT, newLineReg));

        return program;
    }

    private boolean isArrayType(Type type) {
        return type instanceof ArrayType;
    }

    private Program printArray(int tabReg) {
        // Affichage simplifié - Exemple = 1 2 3 4 5
        Program program = new Program();

        // Charger la longueur totale depuis le premier bloc
        int totalLengthReg = newRegister();
        program.addInstruction(new Mem(Mem.Op.LD, totalLengthReg, tabReg));

        // Initialiser le compteur global i et le registre du bloc courant
        int iReg = newRegister();
        program.addInstruction(new UAL(UAL.Op.XOR, iReg, iReg, iReg)); // i = 0

        int currentBlockReg = newRegister();
        program.addInstruction(new UALi(UALi.Op.ADD, currentBlockReg, tabReg, 0)); // commencer au premier bloc

        int posInBlockReg = newRegister();
        program.addInstruction(new UAL(UAL.Op.XOR, posInBlockReg, posInBlockReg, posInBlockReg)); // posInBlock = 0

        // Labels pour la boucle
        String loopLabel = newLabel("print_array_loop");
        String loopEndLabel = newLabel("print_array_end");

        // Début de la boucle
        program.addInstruction(new UALi(UALi.Op.ADD, 0, 0, 0));
        program.getInstructions().getLast().setLabel(loopLabel);

        // Condition : i >= totalLength ?
        program.addInstruction(new CondJump(CondJump.Op.JSEQ, iReg, totalLengthReg, loopEndLabel));

        // Passer au bloc suivant si posInBlock >= 10
        String sameBlockLabel = newLabel("same_block");
        int tenReg = newRegister();
        program.addInstructions(setRegisterTo(tenReg, 10));
        program.addInstruction(
                new CondJump(CondJump.Op.JINF, posInBlockReg, tenReg, sameBlockLabel)
        );

        // -> Changer de bloc
        // Calculer l'adresse du next pointer : currentBlock + 11
        int nextPointerAddrReg = newRegister();
        program.addInstruction(new UALi(UALi.Op.ADD, nextPointerAddrReg, currentBlockReg, 11));

        // Charger l'adresse du prochain bloc
        program.addInstruction(new Mem(Mem.Op.LD, currentBlockReg, nextPointerAddrReg)); // mem[next pointer]
        program.addInstruction(new UAL(UAL.Op.XOR, posInBlockReg, posInBlockReg, posInBlockReg)); // reset posInBlock
        program.addInstruction(new JumpCall(JumpCall.Op.JMP, sameBlockLabel));

        // Label : rester dans le même bloc
        program.addInstruction(new UALi(UALi.Op.ADD, 0, 0, 0));
        program.getInstructions().getLast().setLabel(sameBlockLabel);

        // Lire la valeur : addr = currentBlock + 1 + posInBlock
        int addrReg = newRegister();
        program.addInstruction(new UAL(UAL.Op.ADD, addrReg, currentBlockReg, posInBlockReg));
        program.addInstruction(new UALi(UALi.Op.ADD, addrReg, addrReg, 1));

        int valueReg = newRegister();
        program.addInstruction(new Mem(Mem.Op.LD, valueReg, addrReg));

        // Afficher la valeur
        program.addInstruction(new IO(IO.Op.PRINT, valueReg));

        // Afficher un espace
        int spaceReg = newRegister();
        program.addInstructions(setRegisterTo(spaceReg, 32)); // ASCII 32 = espace
        program.addInstruction(new IO(IO.Op.OUT, spaceReg));

        // Incrémenter i et posInBlock
        program.addInstruction(new UALi(UALi.Op.ADD, iReg, iReg, 1));
        program.addInstruction(new UALi(UALi.Op.ADD, posInBlockReg, posInBlockReg, 1));

        // Retour au début de la boucle
        program.addInstruction(new JumpCall(JumpCall.Op.JMP, loopLabel));

        // Fin de la boucle
        program.addInstruction(new UALi(UALi.Op.ADD, 0, 0, 0));
        program.getInstructions().getLast().setLabel(loopEndLabel);

        return program;
    }
    @Override
    public Program visitDeclaration(grammarTCLParser.DeclarationContext ctx) {
        Program program = new Program();

        String varName = ctx.VAR().getText(); // nom de la variable

        if (ctx.expr() != null) {
            // Cas : déclaration + initialisation
            program.addInstructions(visit(ctx.expr()));      // évaluer l'expression
            int exprReg = regCount - 1;                     // le résultat est dans le dernier registre
            declareVar(varName, exprReg);                  // associer la variable au registre
        } else {
            // Cas : déclaration sans initialisation
            int varReg = newRegister();                    // créer un registre pour la variable
            program.addInstruction(new UAL(UAL.Op.XOR, varReg, varReg, varReg)); // initialise à 0
            declareVar(varName, varReg);                   // associer la variable au registre

            Type varType = getVarType(varName);

            if (varType != null && isArrayType(varType)) {
                // allouer un bloc de tableau vide (longueur 0)
                program.addInstructions(allocateBlock());
                int resultReg = regCount - 1;             // le dernier registre alloué est celui qui contient l'adresse du bloc
                declareVar(varName, resultReg);           // associe la variable au registre contenant l'adresse
            }
        }

        return program;
    }

    @Override
    public Program visitAssignment(grammarTCLParser.AssignmentContext ctx) {
        Program program = new Program();

        String varName = ctx.VAR().getText();      // Nom de la variable
        int varReg = getVar(varName);              // Récupérer le registre où se trouve le tableau ou la variable

        // Récupérer la valeur à assigner (toujours la dernière expression)
        int valueExprIndex = ctx.expr().size() - 1;
        program.addInstructions(visit(ctx.expr(valueExprIndex)));
        int valueReg = regCount - 1;

        // Cas 1 : Variable simple (pas de crochet)
        if (ctx.expr().size() == 1) { // pas d'indice
            program.addInstruction(new UALi(UALi.Op.ADD, varReg, valueReg, 0)); // var = value
        }
        // Cas 2 : Tableau simple (1D) t[i] = value
        else if (ctx.expr().size() == 2) {
            // Évaluer l'indice
            program.addInstructions(visit(ctx.expr(0)));
            int indexReg = regCount - 1;

            // Agrandir le tableau si nécessaire
            program.addInstructions(resizeArrayIfNeeded(varReg, indexReg));

            // Calculer l'adresse de la case à remplir : addr = varReg + index + 1
            int addrReg = newRegister();
            program.addInstruction(new UAL(UAL.Op.ADD, addrReg, varReg, indexReg));
            program.addInstruction(new UALi(UALi.Op.ADD, addrReg, addrReg, 1)); // +1 car case 0 = longueur

            // Stocker la valeur
            program.addInstruction(new Mem(Mem.Op.ST, valueReg, addrReg));
        }

        // Pour l'instant, pas de tableau multidimensionnel
        else {
            throw new UnsupportedOperationException(
                    "Tableaux multidimensionnels non supportés dans cette version"
            );
        }

        return program;
    }

    /**
     * Agrandit un tableau si nécessaire en mettant à jour sa longueur
     * @param tabReg Registre contenant l'adresse du tableau
     * @param indexReg Registre contenant l'index accédé
     * @return Program contenant le code pour agrandir si nécessaire
     */
    private Program resizeArrayIfNeeded(int tabReg, int indexReg) {
        Program program = new Program();

        // Labels
        String noResizeLabel = newLabel("no_resize");
        String resizeLoopLabel = newLabel("resize_loop");
        String resizeEndLabel = newLabel("resize_end");

        // Charger la longueur totale actuelle
        int lengthReg = newRegister();
        program.addInstruction(new Mem(Mem.Op.LD, lengthReg, tabReg));
        // lengthReg = mem[firstBlock]

        // targetLength = index + 1
        int targetLengthReg = newRegister();
        program.addInstruction(new UALi(UALi.Op.ADD, targetLengthReg, indexReg, 1));

        // Si length >= targetLength → pas besoin d’agrandir
        program.addInstruction(
                new CondJump(CondJump.Op.JSEQ, lengthReg, targetLengthReg, noResizeLabel)
        );

        // Trouver le dernier bloc
        int currentBlockReg = newRegister();
        program.addInstruction(new UALi(UALi.Op.ADD, currentBlockReg, tabReg, 0));
        // currentBlock = firstBlock

        // blocks = length / 10
        int blocksReg = newRegister(); //nombre de blocs à parcourir pour atteindre la bonne position
        program.addInstruction(new UALi(UALi.Op.DIV, blocksReg, lengthReg, 10));

        int blockCounterReg = newRegister(); //nombre de blocs déjà parcourus
        program.addInstruction(new UAL(UAL.Op.XOR, blockCounterReg, blockCounterReg, blockCounterReg));

        String findBlockLoop = newLabel("find_block");
        String findBlockEnd = newLabel("find_block_end");

        program.addInstruction(new UALi(UALi.Op.ADD, 0, 0, 0));
        program.getInstructions().getLast().setLabel(findBlockLoop);

        program.addInstruction(
                new CondJump(CondJump.Op.JSEQ, blockCounterReg, blocksReg, findBlockEnd));
        // Si on a déjà parcouru autant (ou plus) de blocs que nécessaire, on arrête la recherche du bloc et on sort de la boucle.

        // currentBlock = mem[currentBlock + 11]
        int nextAddrReg = newRegister();
        program.addInstruction(new UALi(UALi.Op.ADD, nextAddrReg, currentBlockReg, 11));

        int nextBlockReg = newRegister();
        program.addInstruction(new Mem(Mem.Op.LD, nextBlockReg, nextAddrReg));

        program.addInstruction(new UALi(UALi.Op.ADD, currentBlockReg, nextBlockReg, 0));
        // on entre dans le nouveau bloc

        program.addInstruction(new UALi(UALi.Op.ADD, blockCounterReg, blockCounterReg, 1)); //+1 nvx bloc parcouru
        program.addInstruction(new JumpCall(JumpCall.Op.JMP, findBlockLoop));

        program.addInstruction(new UALi(UALi.Op.ADD, 0, 0, 0));
        program.getInstructions().getLast().setLabel(findBlockEnd);

        // posInBlock = length % 10
        int posInBlockReg = newRegister();
        program.addInstruction(new UALi(UALi.Op.MOD, posInBlockReg, lengthReg, 10));

        // Boucle d’agrandissement
        program.addInstruction(new UALi(UALi.Op.ADD, 0, 0, 0));
        program.getInstructions().getLast().setLabel(resizeLoopLabel);

        program.addInstruction(
                new CondJump(CondJump.Op.JSEQ, lengthReg, targetLengthReg, resizeEndLabel)
        );

        // Si posInBlock == 10 → nouveau bloc
        String sameBlockLabel = newLabel("same_block");

        // Registre temporaire pour posInBlock + 1
        int posPlus1Reg = newRegister();
        program.addInstruction(new UALi(UALi.Op.ADD, posPlus1Reg, posInBlockReg, 1));

        // Registre contenant la valeur 9
        int nineReg = newRegister();
        program.addInstructions(setRegisterTo(nineReg, 9));

        // Comparer avec 9 : si posPlus1Reg <= 9 → même bloc
        program.addInstruction(
                new CondJump(CondJump.Op.JIEQ, posPlus1Reg, nineReg, sameBlockLabel));

        // --- Nouveau bloc ---
        Program allocProg = allocateBlock();
        program.addInstructions(allocProg);
        int newBlockReg = regCount - 1;

        // mem[currentBlock + 11] = newBlock
        int linkAddrReg = newRegister();
        program.addInstruction(new UALi(UALi.Op.ADD, linkAddrReg, currentBlockReg, 11));
        program.addInstruction(new Mem(Mem.Op.ST, newBlockReg, linkAddrReg));

        program.addInstruction(new UALi(UALi.Op.ADD, currentBlockReg, newBlockReg, 0));

        program.addInstruction(new UAL(UAL.Op.XOR, posInBlockReg, posInBlockReg, posInBlockReg));

        program.addInstruction(new JumpCall(JumpCall.Op.JMP, sameBlockLabel));

        // --- Même bloc ---
        program.addInstruction(new UALi(UALi.Op.ADD, 0, 0, 0));
        program.getInstructions().getLast().setLabel(sameBlockLabel);

        // mem[currentBlock + 1 + posInBlock] = 0
        int zeroReg = newRegister();
        program.addInstruction(new UAL(UAL.Op.XOR, zeroReg, zeroReg, zeroReg));

        int dataAddrReg = newRegister();
        program.addInstruction(new UAL(UAL.Op.ADD, dataAddrReg, currentBlockReg, posInBlockReg));
        program.addInstruction(new UALi(UALi.Op.ADD, dataAddrReg, dataAddrReg, 1));

        program.addInstruction(new Mem(Mem.Op.ST, zeroReg, dataAddrReg));

        // posInBlock++, length++
        program.addInstruction(new UALi(UALi.Op.ADD, posInBlockReg, posInBlockReg, 1));
        program.addInstruction(new UALi(UALi.Op.ADD, lengthReg, lengthReg, 1));

        program.addInstruction(new JumpCall(JumpCall.Op.JMP, resizeLoopLabel));

        // Mise à jour de la longueur totale
        program.addInstruction(new UALi(UALi.Op.ADD, 0, 0, 0));
        program.getInstructions().getLast().setLabel(resizeEndLabel);

        program.addInstruction(new Mem(Mem.Op.ST, targetLengthReg, tabReg));

        // Pas de resize
        program.addInstruction(new UALi(UALi.Op.ADD, 0, 0, 0));
        program.getInstructions().getLast().setLabel(noResizeLabel);

        return program;
    }

    @Override
    public Program visitIf(grammarTCLParser.IfContext ctx) {
        Program program = new Program();

        // Évaluer la condition
        program.addInstructions(visit(ctx.expr()));
        int condReg = regCount - 1;

        // Labels
        String labelElse = newLabel("Else_");
        String labelEnd = newLabel("EndIf_");

        // Si condition fausse, sauter vers else (ou fin s'il n'y a pas de else)
        String jumpTarget = (ctx.instr().size() > 1) ? labelElse : labelEnd;
        program.addInstruction(new CondJump(CondJump.Op.JEQU, condReg, 0, jumpTarget));

        //  BLOC VRAI
        program.addInstructions(visit(ctx.instr(0)));

        if (ctx.instr().size() > 1) {
            // Il y a un else
            program.addInstruction(new JumpCall(JumpCall.Op.JMP, labelEnd));

            // BLOC ELSE
            program.addInstruction(new UALi(UALi.Op.ADD, 0, 0, 0));
            program.getInstructions().getLast().setLabel(labelElse);

            program.addInstructions(visit(ctx.instr(1)));

            //  FIN DU IF
            program.addInstruction(new UALi(UALi.Op.ADD, 0, 0, 0));
            program.getInstructions().getLast().setLabel(labelEnd);

        } else {
            // Pas de else
            program.addInstruction(new UALi(UALi.Op.ADD, 0, 0, 0));
            program.getInstructions().getLast().setLabel(labelEnd);
        }

        return program;
    }
    @Override
    public Program visitWhile(grammarTCLParser.WhileContext ctx) {
        Program program = new Program();

        String startLabel = newLabel("StartWhile");
        String endLabel = newLabel("EndWhile");

        // Label sera placé sur la première vraie instruction (ADD, LD, etc.)
        boolean labelPlaced = false;

        // Générer le code pour la condition
        Program conditionProgram = visit(ctx.expr());

        for (Instruction instr : conditionProgram.getInstructions()) {
            program.addInstruction(instr);
            if (!labelPlaced) {
                program.getInstructions().getLast().setLabel(startLabel);  // ✓ Label sur première instr
                labelPlaced = true;
            }
        }

        // Registre contenant le résultat
        int condReg = regCount - 1;

        // Sauter à la fin si faux
        program.addInstruction(new CondJump(CondJump.Op.JEQU, condReg, 0, endLabel));

        // Corps
        program.addInstructions(visit(ctx.instr()));

        // Retour
        program.addInstruction(new JumpCall(JumpCall.Op.JMP, startLabel));

        // Fin
        program.addInstruction(new UALi(UALi.Op.ADD, 0, 0, 0));
        program.getInstructions().getLast().setLabel(endLabel);

        return program;
    }

    @Override
    public Program visitFor(grammarTCLParser.ForContext ctx) {
        Program program = new Program();

        String startLabel = newLabel("Dbt_For");
        String endLabel = newLabel("Fin_For");

        enterScope();

        // Initialisation
        program.addInstructions(visit(ctx.instr(0)));

        // Label sur la première instruction de la condition
        boolean labelPlaced = false;
        Program condProgram = visit(ctx.expr());

        for (Instruction instr : condProgram.getInstructions()) {
            program.addInstruction(instr);
            if (!labelPlaced) {
                program.getInstructions().getLast().setLabel(startLabel);  // ✓ Label ici
                labelPlaced = true;
            }
        }

        int condReg = regCount - 1;

        // Test condition
        program.addInstruction(new CondJump(CondJump.Op.JEQU, condReg, 0, endLabel));

        // Corps
        program.addInstructions(visit(ctx.instr(2)));

        // Itération
        program.addInstructions(visit(ctx.instr(1)));

        // Retour
        program.addInstruction(new JumpCall(JumpCall.Op.JMP, startLabel));

        // Fin
        program.addInstruction(new UALi(UALi.Op.ADD, 0, 0, 0));
        program.getInstructions().getLast().setLabel(endLabel);

        exitScope();

        return program;
    }
    @Override
    public Program visitBlock(grammarTCLParser.BlockContext ctx) {
        Program program = new Program();

        enterScope();

        // Boucle sur toutes les instructions du bloc
        for (grammarTCLParser.InstrContext instrCtx : ctx.instr()) {
            program.addInstructions(visit(instrCtx));
        }

        exitScope();

        return program;
    }


    @Override
    public Program visitTab_initialization(grammarTCLParser.Tab_initializationContext ctx) {
        // '{' (expr (',' expr)*)? '}'
        Program program = new Program();

        int totalElements = ctx.expr().size(); // nombre total d'éléments du tableau
        int exprIndex = 0;                     // index pour parcourir les expressions

        // Allouer le premier bloc
        program.addInstructions(allocateBlock());
        int firstBlockReg = regCount - 1; // contient l'adresse du premier bloc
        int currentBlockReg = firstBlockReg;   // registre pour remplir les blocs

        // Stocker la longueur totale dans le premier bloc (case 0)
        int lengthReg = newRegister();
        program.addInstructions(setRegisterTo(lengthReg, totalElements));
        program.addInstruction(new Mem(Mem.Op.ST, lengthReg, currentBlockReg));

        int elementsRemaining = totalElements;

        // Boucle sur les éléments
        while (elementsRemaining > 0) {
            // Combien d'éléments dans ce bloc ? Max 10
            int len = Math.min(10, elementsRemaining);

            // Remplir les cases 1 à len
            for (int i = 0; i < len; i++, exprIndex++) {
                // Évaluer l'expression
                program.addInstructions(visit(ctx.expr(exprIndex)));
                int valueReg = regCount - 1;

                // Calculer l'adresse : currentBlockReg + i + 1
                int addrReg = newRegister();
                program.addInstruction(new UAL(UAL.Op.ADD, addrReg, currentBlockReg, i));
                program.addInstruction(new UALi(UALi.Op.ADD, addrReg, addrReg, 1));

                // Stocker la valeur
                program.addInstruction(new Mem(Mem.Op.ST, valueReg, addrReg));
            }

            elementsRemaining -= len;

            // Si des éléments restent, allouer un nouveau bloc et mettre next (case 11)
            if (elementsRemaining > 0) {
                program.addInstructions(allocateBlock());
                int nextBlockReg = regCount - 1; // contient l'adresse du premier bloc

                // Stocker l'adresse du nouveau bloc dans la case 11 (next)
                int nextAddrReg = newRegister();
                program.addInstruction(new UALi(UALi.Op.ADD, nextAddrReg, nextBlockReg, 0));

                int nextPointerAddrReg = newRegister();
                program.addInstruction(new UALi(UALi.Op.ADD, nextPointerAddrReg, currentBlockReg, 11));
                program.addInstruction(new Mem(Mem.Op.ST, nextAddrReg, nextPointerAddrReg));

                // Passer au nouveau bloc
                currentBlockReg = nextBlockReg;
            } else {
                // Sinon, next = 0 (déjà fait dans allocateBlock)
                break;
            }
        }

        // Copier l'adresse du premier bloc dans un registre final à renvoyer
        int finalReg = newRegister();
        program.addInstruction(new UALi(UALi.Op.ADD, finalReg, firstBlockReg, 0));

        return program;
    }

    private Program allocateBlock() {
        Program program = new Program();

        // Réserver un registre pour l'adresse du bloc
        int blockReg = newRegister();
        program.addInstruction(new UALi(UALi.Op.ADD, blockReg, TP, 0)); // blockReg = TP (adresse du bloc)

        // Initialiser la longueur du bloc à 0 (première case)
        int zeroReg = newRegister();
        program.addInstruction(new UAL(UAL.Op.XOR, zeroReg, zeroReg, zeroReg)); // zeroReg = 0
        program.addInstruction(new Mem(Mem.Op.ST, zeroReg, TP));                // mem[TP] = 0
        program.addInstruction(new UALi(UALi.Op.ADD, TP, TP, 1));              // TP++

        // Initialiser les 10 cases de données à 0
        for (int i = 0; i < 10; i++) {
            int dataReg = newRegister();
            program.addInstruction(new UAL(UAL.Op.XOR, dataReg, dataReg, dataReg)); // dataReg = 0
            program.addInstruction(new Mem(Mem.Op.ST, dataReg, TP));                // mem[TP] = 0
            program.addInstruction(new UALi(UALi.Op.ADD, TP, TP, 1));              // TP++
        }

        // Initialiser la 12ᵉ case (next pointer) à 0
        int nextReg = newRegister();
        program.addInstruction(new UAL(UAL.Op.XOR, nextReg, nextReg, nextReg));
        program.addInstruction(new Mem(Mem.Op.ST, nextReg, TP)); // mem[TP] = 0
        program.addInstruction(new UALi(UALi.Op.ADD, TP, TP, 1)); // TP++

        // Copier blockReg dans un nouveau registre à renvoyer
        int resultReg = newRegister();
        program.addInstruction(new UALi(UALi.Op.ADD, resultReg, blockReg, 0));

        return program;
    }

    @Override
    public Program visitTab_access(grammarTCLParser.Tab_accessContext ctx) {
        // expr '[' expr ']'
        Program program = new Program();

        // Récupérer le registre du tableau
        Program tabProg = visit(ctx.expr(0)); // expression t
        program.addInstructions(tabProg);
        int firstBlockReg = regCount - 1; // contient l'adresse du premier bloc

        // Récupérer l'indice
        Program indexProg = visit(ctx.expr(1)); // expression i
        program.addInstructions(indexProg);
        int indexReg = regCount - 1; // indice global

        // Charger la longueur totale du tableau
        int totalLengthReg = newRegister();
        program.addInstruction(new Mem(Mem.Op.LD, totalLengthReg, firstBlockReg));

        // S'assurer que l'indice est < longueur totale
        String inBoundsLabel = newLabel("tab_access_ok");
        program.addInstruction(new CondJump(CondJump.Op.JINF, indexReg, totalLengthReg, inBoundsLabel));

        // sinon → SegDefault
        // on fait un STOP pour "crash"
        program.addInstruction(new Stop());

        program.addInstruction(new UALi(UALi.Op.ADD, 0, 0, 0));
        program.getInstructions().getLast().setLabel(inBoundsLabel);

        // Trouver le bloc contenant l'élément
        int currentBlockReg = newRegister();
        program.addInstruction(new UALi(UALi.Op.ADD, currentBlockReg, firstBlockReg, 0));

        int blockCounterReg = newRegister();
        program.addInstruction(new UAL(UAL.Op.XOR, blockCounterReg, blockCounterReg, blockCounterReg)); // compteur de blocs

        int tenReg = newRegister();
        program.addInstructions(setRegisterTo(tenReg, 10));

        String blockLoopLabel = newLabel("tab_block_loop");
        String blockLoopEndLabel = newLabel("tab_block_loop_end");

        // Boucle blocklooplabel : index >= 10 ? aller au bloc suivant
        program.addInstruction(new UALi(UALi.Op.ADD, 0, 0, 0));
        program.getInstructions().getLast().setLabel(blockLoopLabel);

        program.addInstruction(new CondJump(CondJump.Op.JINF, indexReg, tenReg, blockLoopEndLabel));
        // si index < 10 -> fin boucle

        // Passer au bloc suivant : currentBlockReg = mem[currentBlockReg + 11]
        int nextBlockAddrReg = newRegister();
        program.addInstruction(new UALi(UALi.Op.ADD, nextBlockAddrReg, currentBlockReg, 11));
        program.addInstruction(new Mem(Mem.Op.LD, currentBlockReg, nextBlockAddrReg));

        // Décrémenter l'indice global : indexReg -= 10
        program.addInstruction(new UAL(UAL.Op.SUB, indexReg, indexReg, tenReg));

        // Retour au début de la boucle
        program.addInstruction(new JumpCall(JumpCall.Op.JMP, blockLoopLabel));

        // Fin de la boucle
        program.addInstruction(new UALi(UALi.Op.ADD, 0, 0, 0));
        program.getInstructions().getLast().setLabel(blockLoopEndLabel);

        // Calculer l'adresse finale de l'élément dans le bloc
        int elementAddrReg = newRegister();
        program.addInstruction(new UALi(UALi.Op.ADD, elementAddrReg, currentBlockReg, 1)); // +1 car case 0 = longueur locale
        program.addInstruction(new UAL(UAL.Op.ADD, elementAddrReg, elementAddrReg, indexReg));

        // Charger la valeur
        int valueReg = newRegister();
        program.addInstruction(new Mem(Mem.Op.LD, valueReg, elementAddrReg));

        return program;
    }


    @Override
    public Program visitBrackets(grammarTCLParser.BracketsContext ctx) {
        Program program = new Program();

        // Visite l'expression contenue entre les crochets
        Program exprProgram = visit(ctx.expr());

        // Ajoute les instructions générées pour l'expression entre crochets
        program.addInstructions(exprProgram);

        return program;
    }

    @Override
    public Program visitBase_type(grammarTCLParser.Base_typeContext ctx) throws RuntimeException {
        throw new RuntimeException("Method 'visitBase_type' should not be called");
    }

    @Override
    public Program visitTab_type(grammarTCLParser.Tab_typeContext ctx) throws RuntimeException {
        throw new RuntimeException("Method 'visitTab_type' should not be called");
    }

    @Override
    public Program visitCall(grammarTCLParser.CallContext ctx) {
        Program program = new Program();

        String functionName = ctx.VAR().getText();
        int nbArgs = ctx.expr().size();

        //  Évaluer tous les arguments
        ArrayList<Integer> argRegisters = new ArrayList<>();
        for (int i = 0; i < nbArgs; i++) {
            program.addInstructions(visit(ctx.expr(i)));
            argRegisters.add(regCount - 1);
        }

        //  Déterminer le dernier registre utilisé APRÈS évaluation des arguments
        int lastUsedRegister = regCount - 1;

        //   sauvegarder TOUS les registres actifs
        //    (sauf le registre de retour qui sera écrasé)
        for (int i = this.startReg; i <= lastUsedRegister; i++) {
            program.addInstruction(new Mem(Mem.Op.ST, i, SP));
            program.addInstruction(new UALi(UALi.Op.ADD, SP, SP, 1));
        }

        // Empiler les arguments (UNE SEULE FOIS)
        for (int reg : argRegisters) {
            program.addInstruction(new Mem(Mem.Op.ST, reg, SP));
            program.addInstruction(new UALi(UALi.Op.ADD, SP, SP, 1));
        }

        // Appel de la fonction
        program.addInstruction(new JumpCall(JumpCall.Op.CALL, functionName));

        //  Récupérer le résultat dans un NOUVEAU registre
        int resultReg = newRegister();
        program.addInstruction(new Mem(Mem.Op.LD, resultReg, SP));

        //  Dépiler les arguments
        if (nbArgs > 0) {
            program.addInstruction(new UALi(UALi.Op.SUB, SP, SP, nbArgs));
        }

        //  restaurer les registres dans l'ordre INVERSE
        for (int i = lastUsedRegister; i >= this.startReg; i--) {
            program.addInstruction(new UALi(UALi.Op.SUB, SP, SP, 1));
            program.addInstruction(new Mem(Mem.Op.LD, i, SP));
        }

        return program;
    }

    @Override
    public Program visitDecl_fct(grammarTCLParser.Decl_fctContext ctx) {
        Program program = new Program();

        String functionName = ctx.VAR(0).getText();

        // Label de la fonction
        program.addInstruction(new UALi(UALi.Op.ADD, 0, 0, 0));
        program.getInstructions().getLast().setLabel(functionName);

        enterScope();

        int oldStart = this.startReg;
        this.startReg = regCount;

        int nbArgs = ctx.VAR().size() - 1;

        // Charger les arguments depuis la pile
        for (int i = 0; i < nbArgs; i++) {
            String argName = ctx.VAR(i + 1).getText();

            // Calculer l'offset depuis SP
            int offset = nbArgs - i;

            // Créer un registre temporaire pour l'adresse
            int addrReg = newRegister();
            program.addInstruction(new UALi(UALi.Op.SUB, addrReg, SP, offset));

            // Charger l'argument dans un nouveau registre
            int argReg = newRegister();
            program.addInstruction(new Mem(Mem.Op.LD, argReg, addrReg));

            // Associer la variable au registre
            declareVar(argName, argReg);
        }

        // Corps de la fonction
        program.addInstructions(visitCore_fct(ctx.core_fct()));

        exitScope();
        this.startReg = oldStart;

        return program;
    }

    @Override
    public Program visitReturn(grammarTCLParser.ReturnContext ctx) {
        Program program = new Program();

        // Évaluer l'expression retournée
        program.addInstructions(visit(ctx.expr()));
        int resultReg = regCount - 1;

        // Stocker le résultat dans le registre de retour
        program.addInstruction(new Mem(Mem.Op.ST, resultReg, SP));

        // Retour
        program.addInstruction(new Ret());

        return program;
    }

    @Override
    public Program visitCore_fct(grammarTCLParser.Core_fctContext ctx) {
        Program program = new Program();

        // Instructions du corps
        for (grammarTCLParser.InstrContext instrContext : ctx.instr()) {
            program.addInstructions(visit(instrContext));
        }

        // S'il y a un return expr implicite à la fin
        if (ctx.expr() != null) {
            program.addInstructions(visit(ctx.expr()));
            int resultReg = regCount - 1;

            // Stocker le résultat dans le registre de retour
            program.addInstruction(new Mem(Mem.Op.ST, resultReg, SP));

            program.addInstruction(new Ret());
        }

        return program;
    }

    @Override
    public Program visitMain(grammarTCLParser.MainContext ctx) {
        Program program = new Program();

        enterScope(); // Scope global

        //  INITIALISATION DES REGISTRES SYSTÈME
        program.addInstruction(new UAL(UAL.Op.XOR, 0, 0, 0));
        program.addInstruction(new UALi(UALi.Op.ADD, 1, 0, 1));
        program.addInstruction(new UAL(UAL.Op.XOR, 2, 2, 2));

        regCount = 3;

        //  APPEL À MAIN
        program.addInstruction(new JumpCall(JumpCall.Op.CALL, "main"));
        program.addInstruction(new Stop());

        enterScope();
        this.startReg = regCount;

        // Générer le corps de main
        Program mainBody = visitCore_fct(ctx.core_fct());

        // Attacher le label "main" à la première instruction du corps
        if (!mainBody.getInstructions().isEmpty()) {
            mainBody.getInstructions().getFirst().setLabel("main");
        } else {
            // Cas edge : corps vide, créer une NOp
            program.addInstruction(new UALi(UALi.Op.ADD, 0, 0, 0));
            program.getInstructions().getLast().setLabel("main");
        }

        program.addInstructions(mainBody);

        exitScope();

        // GÉNÉRER LES AUTRES FONCTIONS
        for (grammarTCLParser.Decl_fctContext decl : ctx.decl_fct()) {
            program.addInstructions(visitDecl_fct(decl));
        }

        exitScope(); // Fin scope global

        return program;
    }
}