
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

import Asm.*;

import org.antlr.v4.runtime.tree.AbstractParseTreeVisitor;
import Type.PrimitiveType;
import Type.Type;
import Type.UnknownType;



public class CodeGenerator  extends AbstractParseTreeVisitor<Program> implements grammarTCLVisitor<Program> {

    private Map<UnknownType,Type> types;
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
    public CodeGenerator(Map<UnknownType, Type> types) {
        this.types = types;
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

        // Labels
        String trueLabel = newLabel("eq_true");
        String endLabel  = newLabel("eq_end");

        // ✓ Évaluer l'expression gauche AVANT de créer resultReg
        program.addInstructions(visit(ctx.expr(0)));
        int leftReg = regCount - 1;

        // ✓ Évaluer l'expression droite
        program.addInstructions(visit(ctx.expr(1)));
        int rightReg = regCount - 1;

        // ✓ Maintenant créer le registre résultat
        int resultReg = newRegister();

        // Test == ou !=
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

        // Faux → result = 0
        program.addInstruction(
                new UAL(UAL.Op.XOR, resultReg, resultReg, resultReg)
        );
        program.addInstruction(new JumpCall(JumpCall.Op.JMP, endLabel));

        // Vrai → result = 1
        Program trueProg = new Program();
        trueProg.addInstruction(
                new UAL(UAL.Op.XOR, resultReg, resultReg, resultReg)
        );
        trueProg.addInstruction(
                new UALi(UALi.Op.ADD, resultReg, resultReg, 1)
        );
        trueProg.getInstructions().getFirst().setLabel(trueLabel);
        program.addInstructions(trueProg);

        // Fin
        Program endProg = new Program();
        endProg.addInstruction(
                new UALi(UALi.Op.ADD, resultReg, resultReg, 0)
        );
        endProg.getInstructions().getFirst().setLabel(endLabel);
        program.addInstructions(endProg);

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
        Program program = new Program();

        String varName = ctx.VAR().getText();

        // ✓ Récupérer le registre de la variable
        Integer varReg = getVar(varName);

        // ✓ Récupérer le type depuis la map fournie par le groupe 1
        Type varType = null;
        for (Map.Entry<UnknownType, Type> entry : types.entrySet()) {
            if (entry.getKey().getVarName().equals(varName)) {
                varType = entry.getValue();
                break;
            }
        }

        // Si le type n'est pas trouvé, supposer que c'est un int
        if (varType == null) {
            varType = new PrimitiveType(PrimitiveType.Base.INT);
        }

        // Vérifier si c'est un tableau
        if (isArrayType(varType)) {
            program.addInstructions(printArray(varReg, varType));
        } else {
            // ✓ TYPE PRIMITIF : print direct du registre
            program.addInstruction(new IO(IO.Op.PRINT, varReg));
        }

        // ✓ AJOUTER UNE NOUVELLE LIGNE après chaque print
        int newLineReg = newRegister();
        program.addInstructions(setRegisterTo(newLineReg, 10)); // ASCII 10 = '\n'
        program.addInstruction(new IO(IO.Op.OUT, newLineReg));

        return program;
    }


    /**
     * Vérifie si un type est un tableau
     */
    private boolean isArrayType(Type type) {
        // Adaptez selon votre hiérarchie de types
        return type.toString().toLowerCase().contains("tab");
    }

    /**
     * Génère le code pour afficher un tableau
     * AVEC UNE VRAIE BOUCLE EN ASSEMBLEUR
     */
    private Program printArray(int tabReg, Type arrayType) {
        Program program = new Program();

        // 1. Charger la longueur du tableau (case 0)
        int lengthReg = newRegister();
        program.addInstruction(new Mem(Mem.Op.LD, lengthReg, tabReg));

        // 2. Initialiser le compteur de boucle
        int iReg = newRegister();
        program.addInstruction(new UAL(UAL.Op.XOR, iReg, iReg, iReg)); // i = 0

        // 3. Labels pour la boucle
        String loopLabel = newLabel("print_array_loop");
        String endLabel = newLabel("print_array_end");

        // 4. Début de la boucle
        program.addInstruction(new UALi(UALi.Op.ADD, 0, 0, 0));
        program.getInstructions().getLast().setLabel(loopLabel);

        // 5. Condition : i >= length ? sortir
        program.addInstruction(new CondJump(CondJump.Op.JSEQ, iReg, lengthReg, endLabel));

        // 6. Calculer l'adresse de l'élément : addr = tabReg + i + 1
        int addrReg = newRegister();
        program.addInstruction(new UAL(UAL.Op.ADD, addrReg, tabReg, iReg));
        program.addInstruction(new UALi(UALi.Op.ADD, addrReg, addrReg, 1)); // +1 car case 0 = longueur

        // 7. Charger la valeur : value = mem[addr]
        int valueReg = newRegister();
        program.addInstruction(new Mem(Mem.Op.LD, valueReg, addrReg));

        // 8. Afficher la valeur
        program.addInstruction(new IO(IO.Op.PRINT, valueReg));

        // ✓ 9. Afficher un espace (ASCII 32)
        int spaceReg = newRegister();
        program.addInstructions(setRegisterTo(spaceReg, 32)); // ASCII 32 = espace
        program.addInstruction(new IO(IO.Op.OUT, spaceReg));

        // 10. Incrémenter i
        program.addInstruction(new UALi(UALi.Op.ADD, iReg, iReg, 1));

        // 11. Retour au début de la boucle
        program.addInstruction(new JumpCall(JumpCall.Op.JMP, loopLabel));

        // 12. Fin de la boucle
        program.addInstruction(new UALi(UALi.Op.ADD, 0, 0, 0));
        program.getInstructions().getLast().setLabel(endLabel);

        return program;
    }
    @Override
    public Program visitDeclaration(grammarTCLParser.DeclarationContext ctx) {
        Program program = new Program();

        String varName = ctx.VAR().getText();

        if (ctx.expr() != null) {
            // Cas avec initialisation
            program.addInstructions(visit(ctx.expr()));
            int exprReg = regCount - 1;
            declareVar(varName, exprReg);

        } else {
            // Cas sans initialisation
            int varRegister = newRegister();
            program.addInstruction(new UAL(UAL.Op.XOR, varRegister, varRegister, varRegister));
            declareVar(varName, varRegister);


            // Si c'est un tableau, initialiser sa longueur à 0
            Type varType = null;
            for (Map.Entry<UnknownType, Type> entry : types.entrySet()) {
                if (entry.getKey().getVarName().equals(varName)) {
                    varType = entry.getValue();
                    break;
                }
            }

            if (varType != null && isArrayType(varType)) {
                int zeroReg = newRegister();
                program.addInstruction(new UAL(UAL.Op.XOR, zeroReg, zeroReg, zeroReg));
                program.addInstruction(new Mem(Mem.Op.ST, zeroReg, varRegister));
                // mem[tabReg] = 0
            }
        }

        return program;
    }


    @Override
    public Program visitAssignment(grammarTCLParser.AssignmentContext ctx) {
        // VAR ('[' expr ']')* ASSIGN expr SEMICOL
        Program program = new Program();

        String varName = ctx.VAR().getText();
        int varReg = getVar(varName);

        // Nombre de paires de crochets : t[2][3] → 2 crochets
        // ctx.expr().size() = nombre total d'expressions
        // La dernière expression est la valeur à assigner
        int bracketsCount = ctx.expr().size() - 1;

        // Évaluer l'expression à droite (la valeur à assigner)
        program.addInstructions(visit(ctx.expr(ctx.expr().size() - 1)));
        int valueReg = regCount - 1;

        if (bracketsCount == 0) {
            // ========================================
            // CAS 1 : Affectation variable simple
            // x = 5;
            // ========================================
            program.addInstruction(new UALi(UALi.Op.ADD, varReg, valueReg, 0));

        } else if (bracketsCount == 1) {
            // ========================================
            // CAS 2 : Affectation tableau simple
            // t[x] = 5;
            // ========================================

            // Évaluer l'index
            program.addInstructions(visit(ctx.expr(0)));
            int indexReg = regCount - 1;

            // Agrandir le tableau si nécessaire
            program.addInstructions(resizeArrayIfNeeded(varReg, indexReg));

            // Calculer l'adresse : addr = varReg + indexReg + 1
            int addrReg = newRegister();
            program.addInstruction(new UAL(UAL.Op.ADD, addrReg, varReg, indexReg));
            program.addInstruction(new UALi(UALi.Op.ADD, addrReg, addrReg, 1)); // +1 car case 0 = longueur

            // Stocker la valeur
            program.addInstruction(new Mem(Mem.Op.ST, valueReg, addrReg));

        } else {
            // ========================================
            // CAS 3 : Tableaux multidimensionnels
            // t[x][y] = 5; ou t[x][y][z] = 5;
            // ========================================

            int currentReg = varReg;

            for (int i = 0; i < bracketsCount; i++) {
                // Évaluer l'index à ce niveau
                program.addInstructions(visit(ctx.expr(i)));
                int indexReg = regCount - 1;

                if (i < bracketsCount - 1) {
                    // Pas encore au dernier niveau : charger le sous-tableau

                    // Agrandir si nécessaire
                    program.addInstructions(resizeArrayIfNeeded(currentReg, indexReg));

                    // Calculer l'adresse
                    int addrReg = newRegister();
                    program.addInstruction(new UAL(UAL.Op.ADD, addrReg, currentReg, indexReg));
                    program.addInstruction(new UALi(UALi.Op.ADD, addrReg, addrReg, 1));

                    // Charger le sous-tableau
                    int subArrayReg = newRegister();
                    program.addInstruction(new Mem(Mem.Op.LD, subArrayReg, addrReg));

                    currentReg = subArrayReg;

                } else {
                    // Dernier niveau : affecter la valeur

                    // Agrandir si nécessaire
                    program.addInstructions(resizeArrayIfNeeded(currentReg, indexReg));

                    // Calculer l'adresse finale
                    int addrReg = newRegister();
                    program.addInstruction(new UAL(UAL.Op.ADD, addrReg, currentReg, indexReg));
                    program.addInstruction(new UALi(UALi.Op.ADD, addrReg, addrReg, 1));

                    // Stocker la valeur
                    program.addInstruction(new Mem(Mem.Op.ST, valueReg, addrReg));
                }
            }
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

        String okLabel = newLabel("array_size_ok");
        String endLabel = newLabel("resize_end");

        // 1. Charger la longueur actuelle
        int lengthReg = newRegister();
        program.addInstruction(new Mem(Mem.Op.LD, lengthReg, tabReg));

        // 2. Si index < length, OK
        program.addInstruction(new CondJump(CondJump.Op.JINF, indexReg, lengthReg, okLabel));

        // 3. Nouvelle longueur = index + 1
        int newLengthReg = newRegister();
        program.addInstruction(new UALi(UALi.Op.ADD, newLengthReg, indexReg, 1));

        // 4. Boucle pour initialiser les nouvelles cases à 0
        String loopLabel = newLabel("init_loop");
        String loopEndLabel = newLabel("init_end");

        // i = oldLength
        int iReg = newRegister();
        program.addInstruction(new UALi(UALi.Op.ADD, iReg, lengthReg, 0));

        // Label début boucle
        program.addInstruction(new UALi(UALi.Op.ADD, 0, 0, 0));
        program.getInstructions().getLast().setLabel(loopLabel);

        // Condition : i >= newLength ? fin
        program.addInstruction(new CondJump(CondJump.Op.JSEQ, iReg, newLengthReg, loopEndLabel));

        // Calculer adresse : tabReg + i + 1
        int addrReg = newRegister();
        program.addInstruction(new UAL(UAL.Op.ADD, addrReg, tabReg, iReg));
        program.addInstruction(new UALi(UALi.Op.ADD, addrReg, addrReg, 1));

        // Stocker 0
        int zeroReg = newRegister();
        program.addInstruction(new UAL(UAL.Op.XOR, zeroReg, zeroReg, zeroReg));
        program.addInstruction(new Mem(Mem.Op.ST, zeroReg, addrReg));

        // i++
        program.addInstruction(new UALi(UALi.Op.ADD, iReg, iReg, 1));
        program.addInstruction(new JumpCall(JumpCall.Op.JMP, loopLabel));

        // Fin boucle
        program.addInstruction(new UALi(UALi.Op.ADD, 0, 0, 0));
        program.getInstructions().getLast().setLabel(loopEndLabel);

        // 5. Mettre à jour la longueur
        program.addInstruction(new Mem(Mem.Op.ST, newLengthReg, tabReg));

        program.addInstruction(new JumpCall(JumpCall.Op.JMP, endLabel));

        // Label OK
        program.addInstruction(new UALi(UALi.Op.ADD, 0, 0, 0));
        program.getInstructions().getLast().setLabel(okLabel);

        // Label fin
        program.addInstruction(new UALi(UALi.Op.ADD, 0, 0, 0));
        program.getInstructions().getLast().setLabel(endLabel);

        return program;
    }

    @Override
    public Program visitIf(grammarTCLParser.IfContext ctx) {
        Program program = new Program();

        // Évaluer la condition
        program.addInstructions(visit(ctx.expr()));
        int condReg = regCount - 1;

        // Labels
        String labelElseOrEnd = (ctx.instr().size() > 1) ? newLabel("Else_") : newLabel("EndIf_");
        String labelEnd = newLabel("EndIf_");

        // Si condition fausse, sauter vers else ou fin
        program.addInstruction(new CondJump(CondJump.Op.JEQU, condReg, 0, labelElseOrEnd));

        // Bloc vrai
        program.addInstructions(visit(ctx.instr(0)));

        if (ctx.instr().size() > 1) { // else présent
            program.addInstruction(new JumpCall(JumpCall.Op.JMP, labelEnd));

            // Bloc faux (else)
            Program falseProg = visit(ctx.instr(1));
            if (!falseProg.getInstructions().isEmpty())
                falseProg.getInstructions().getFirst().setLabel(labelElseOrEnd);
            program.addInstructions(falseProg);

            // Fin du if
            Program endProg = new Program();
            endProg.addInstruction(new UALi(UALi.Op.ADD, 0, 0, 0));
            endProg.getInstructions().getFirst().setLabel(labelEnd);
            program.addInstructions(endProg);

        } else {
            // Pas de else, mettre le label de fin
            Program endProg = new Program();
            endProg.addInstruction(new UALi(UALi.Op.ADD, 0, 0, 0));
            endProg.getInstructions().getFirst().setLabel(labelElseOrEnd);
            program.addInstructions(endProg);
        }

        return program;
    }
    @Override
    public Program visitWhile(grammarTCLParser.WhileContext ctx) {
        Program program = new Program();

        // Création des labels pour le début et la fin de la boucle
        String startLabel = newLabel("StartWhile");
        String endLabel = newLabel("EndWhile");

        // Instruction factice pour le label de début
        program.addInstruction(new UALi(UALi.Op.ADD, 0, 0, 0));
        program.getInstructions().getLast().setLabel(startLabel);

        // Générer le code pour la condition
        Program conditionProgram = visit(ctx.expr());
        program.addInstructions(conditionProgram);

        // Registre contenant le résultat de la condition
        int condReg = regCount - 1;

        // Instruction factice pour comparer à 0
        int zeroReg = newRegister();
        program.addInstruction(new UALi(UALi.Op.ADD, zeroReg, 0, 0));

        // Sauter à la fin si la condition est fausse
        program.addInstruction(new CondJump(CondJump.Op.JEQU, condReg, zeroReg, endLabel));

        // Entrée dans le bloc de la boucle
        program.addInstructions(visit(ctx.instr()));

        // Retour au début de la boucle
        program.addInstruction(new JumpCall(JumpCall.Op.JMP, startLabel));

        // Instruction factice pour la fin de la boucle avec label
        program.addInstruction(new UALi(UALi.Op.ADD, 0, 0, 0));
        program.getInstructions().getLast().setLabel(endLabel);

        return program;
    }

    @Override
    public Program visitFor(grammarTCLParser.ForContext ctx) {
        Program program = new Program();

        String startLabel = newLabel("Dbt_For");
        String endLabel = newLabel("Fin_For");

        // Scope pour le for
        enterScope();

        // --- Initialisation ---
        program.addInstructions(visit(ctx.instr(0)));

        //  Label de début AVANT la condition
        program.addInstruction(new UALi(UALi.Op.ADD, 0, 0, 0));
        program.getInstructions().getLast().setLabel(startLabel);

        // --- Condition (réévaluée à chaque itération) ---
        program.addInstructions(visit(ctx.expr()));
        int condReg = regCount - 1;

        // Test condition
        program.addInstruction(new CondJump(CondJump.Op.JEQU, condReg, 0, endLabel));

        // --- Corps ---
        program.addInstructions(visit(ctx.instr(2)));

        // --- Itération ---
        program.addInstructions(visit(ctx.instr(1)));

        // Retour au début
        program.addInstruction(new JumpCall(JumpCall.Op.JMP, startLabel));

        // Label de fin
        program.addInstruction(new UALi(UALi.Op.ADD, 0, 0, 0));
        program.getInstructions().getLast().setLabel(endLabel);

        //  Sortir du scope
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
        Program program = new Program();

        int size = ctx.expr().size();

        // ✓ Créer un registre pour stocker l'adresse de début du tableau
        int tableAddrReg = newRegister();

        // ✓ Sauvegarder l'adresse actuelle du heap (TP) dans tableAddrReg
        program.addInstruction(new UALi(UALi.Op.ADD, tableAddrReg, this.TP, 0));

        // ✓ Créer un registre pour la longueur
        int lengthReg = newRegister();
        program.addInstructions(this.setRegisterTo(lengthReg, size));

        // ✓ Stocker la longueur à l'adresse TP
        program.addInstruction(new Mem(Mem.Op.ST, lengthReg, this.TP));

        // ✓ Avancer TP de 1 pour pointer sur la première case de données
        program.addInstruction(new UALi(UALi.Op.ADD, this.TP, this.TP, 1));

        // ✓ Boucle sur toutes les expressions pour remplir le tableau
        for (grammarTCLParser.ExprContext exprCtx : ctx.expr()) {
            program.addInstructions(visit(exprCtx));
            int exprReg = regCount - 1;

            // Stocker la valeur à l'adresse TP
            program.addInstruction(new Mem(Mem.Op.ST, exprReg, this.TP));

            // Avancer TP de 1
            program.addInstruction(new UALi(UALi.Op.ADD, this.TP, this.TP, 1));
        }

        // ✓ tableAddrReg contient maintenant l'adresse du tableau
        // C'est le dernier registre alloué qui sera récupéré par regCount-1
        // Mais attendez... tableAddrReg n'est plus regCount-1 !

        // ✓ Solution : copier tableAddrReg dans un nouveau registre final
        int finalReg = newRegister();
        program.addInstruction(new UALi(UALi.Op.ADD, finalReg, tableAddrReg, 0));

        return program;
    }

    @Override
    public Program visitTab_access(grammarTCLParser.Tab_accessContext ctx) {
        Program program = new Program();

        // Récupérer registre du tableau
        Program tabProg = visit(ctx.expr(0));  // l'expression t
        program.addInstructions(tabProg);
        int tabReg = regCount - 1;

        // Récupérer registre de l'indice
        Program indexProg = visit(ctx.expr(1)); // l'expression i
        program.addInstructions(indexProg);
        int indexReg = regCount - 1;

        // Registre pour la longueur
        int lengthReg = newRegister();
        program.addInstruction(new Mem(Mem.Op.LD, lengthReg, tabReg)); // longueur = tab[0]

        // Registre pour l'adresse de l'élément tab[i]
        int elementAddrReg = newRegister();
        program.addInstruction(new UAL(UAL.Op.ADD, elementAddrReg, tabReg, indexReg));
        program.addInstruction(new UALi(UALi.Op.ADD, elementAddrReg, elementAddrReg, 1)); // +1 car case 0 = longueur

        // Charger la valeur dans un nouveau registre
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

        // 1. Évaluer tous les arguments
        ArrayList<Integer> argRegisters = new ArrayList<>();
        for (int i = 0; i < nbArgs; i++) {
            program.addInstructions(visit(ctx.expr(i)));
            argRegisters.add(regCount - 1);
        }

        // 2. Déterminer le dernier registre utilisé APRÈS évaluation des arguments
        int lastUsedRegister = regCount - 1;

        // 3. Si appel récursif : sauvegarder TOUS les registres actifs
        //    (sauf le registre de retour qui sera écrasé)
        for (int i = this.startReg; i <= lastUsedRegister; i++) {
            program.addInstruction(new Mem(Mem.Op.ST, i, SP));
            program.addInstruction(new UALi(UALi.Op.ADD, SP, SP, 1));
        }

        // 4. Empiler les arguments (UNE SEULE FOIS)
        for (int reg : argRegisters) {
            program.addInstruction(new Mem(Mem.Op.ST, reg, SP));
            program.addInstruction(new UALi(UALi.Op.ADD, SP, SP, 1));
        }

        // 5. Appel de la fonction
        program.addInstruction(new JumpCall(JumpCall.Op.CALL, functionName));

        // 6. Récupérer le résultat dans un NOUVEAU registre
        int resultReg = newRegister();
        program.addInstruction(new Mem(Mem.Op.LD, resultReg, SP));

        // 7. Dépiler les arguments
        if (nbArgs > 0) {
            program.addInstruction(new UALi(UALi.Op.SUB, SP, SP, nbArgs));
        }

        // 8. Si appel récursif : restaurer les registres dans l'ordre INVERSE
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
        // Arguments empilés : arg0, arg1, arg2, ... (dans cet ordre)
        // Donc à SP-nbArgs on a arg0, à SP-nbArgs+1 on a arg1, etc.
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

        // Initialisation des registres système
        program.addInstruction(new UAL(UAL.Op.XOR, 0, 0, 0));
        program.addInstruction(new UALi(UALi.Op.ADD, 1, 0, 1));
        program.addInstruction(new UAL(UAL.Op.XOR, 2, 2, 2));

        regCount = 3; // Réserver R0, R1, R2

        // Appeler la fonction main
        program.addInstruction(new JumpCall(JumpCall.Op.CALL, "main"));
        program.addInstruction(new Stop());

        // === PREMIÈRE PASSE : Générer le code de main ===
        program.addInstruction(new UALi(UALi.Op.ADD, 0, 0, 0));
        program.getInstructions().getLast().setLabel("main");

        enterScope();

        this.startReg = regCount;

        // Corps de main
        program.addInstructions(visitCore_fct(ctx.core_fct()));

        exitScope();

        // === DEUXIÈME PASSE : Générer le code des autres fonctions ===
        for (grammarTCLParser.Decl_fctContext decl : ctx.decl_fct()) {
            program.addInstructions(visitDecl_fct(decl));
        }

        exitScope(); // Fin scope global

        return program;
    }
}