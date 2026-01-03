
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

    private Program printArray(int tabReg, Type arrayType) {
        Program program = new Program();

        int lengthReg = newRegister();
        program.addInstruction(new Mem(Mem.Op.LD, lengthReg, tabReg));

        int iReg = newRegister();
        program.addInstruction(new UAL(UAL.Op.XOR, iReg, iReg, iReg));

        String loopLabel = newLabel("print_array_loop");
        String endLabel = newLabel("print_array_end");

        program.addInstruction(new CondJump(CondJump.Op.JSEQ, iReg, lengthReg, endLabel));
        program.getInstructions().getLast().setLabel(loopLabel);

        int addrReg = newRegister();
        program.addInstruction(new UAL(UAL.Op.ADD, addrReg, tabReg, iReg));
        program.addInstruction(new UALi(UALi.Op.ADD, addrReg, addrReg, 1));

        int valueReg = newRegister();
        program.addInstruction(new Mem(Mem.Op.LD, valueReg, addrReg));

        program.addInstruction(new IO(IO.Op.PRINT, valueReg));

        int spaceReg = newRegister();
        program.addInstructions(setRegisterTo(spaceReg, 32));
        program.addInstruction(new IO(IO.Op.OUT, spaceReg));

        program.addInstruction(new UALi(UALi.Op.ADD, iReg, iReg, 1));
        program.addInstruction(new JumpCall(JumpCall.Op.JMP, loopLabel));

        program.addInstruction(new UALi(UALi.Op.ADD, 0, 0, 0));
        program.getInstructions().getLast().setLabel(endLabel);

        return program;
    }
    @Override
    public Program visitDeclaration(grammarTCLParser.DeclarationContext ctx) {
        Program program = new Program();

        String varName = ctx.VAR().getText();

        if (ctx.expr() != null) {
            program.addInstructions(visit(ctx.expr()));
            int exprReg = regCount - 1;
            declareVar(varName, exprReg);
        } else {
            int varRegister = newRegister();
            program.addInstruction(new UAL(UAL.Op.XOR, varRegister, varRegister, varRegister));
            declareVar(varName, varRegister);

            //  CORRECTION : Utiliser getVarType()
            Type varType = getVarType(varName);

            if (varType != null && isArrayType(varType)) {
                int zeroReg = newRegister();
                program.addInstruction(new UAL(UAL.Op.XOR, zeroReg, zeroReg, zeroReg));
                program.addInstruction(new Mem(Mem.Op.ST, zeroReg, varRegister));
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
            // CAS 1 : Affectation variable simple
            program.addInstruction(new UALi(UALi.Op.ADD, varReg, valueReg, 0));

        } else if (bracketsCount == 1) {
            // CAS 2 : Affectation tableau simple
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
            // CAS 3 : Tableaux multidimensionnels
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
        String loopLabel = newLabel("init_loop");
        String loopEndLabel = newLabel("init_end");

        // 1. Charger la longueur actuelle
        int lengthReg = newRegister();
        program.addInstruction(new Mem(Mem.Op.LD, lengthReg, tabReg));

        // 2. Si index < length, sauter au label OK
        program.addInstruction(new CondJump(CondJump.Op.JINF, indexReg, lengthReg, okLabel));

        // 3. Nouvelle longueur = index + 1
        int newLengthReg = newRegister();
        program.addInstruction(new UALi(UALi.Op.ADD, newLengthReg, indexReg, 1));

        // 4. Initialiser i = oldLength
        int iReg = newRegister();
        program.addInstruction(new UALi(UALi.Op.ADD, iReg, lengthReg, 0));

        // DÉBUT DE BOUCLE
        program.addInstruction(new CondJump(CondJump.Op.JSEQ, iReg, newLengthReg, loopEndLabel));
        program.getInstructions().getLast().setLabel(loopLabel);  // ✓ Label sur JSEQ

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

        //  FIN DE BOUCLE
        program.addInstruction(new Mem(Mem.Op.ST, newLengthReg, tabReg));
        program.getInstructions().getLast().setLabel(loopEndLabel);  // ✓ Label sur ST

        // Sauter à la fin
        program.addInstruction(new JumpCall(JumpCall.Op.JMP, endLabel));

        //  LABEL OK (tableau assez grand)
        program.addInstruction(new UALi(UALi.Op.ADD, 0, 0, 0));  // NOp
        program.getInstructions().getLast().setLabel(okLabel);

        //  FIN
        program.addInstruction(new UALi(UALi.Op.ADD, 0, 0, 0));  // NOp
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
        Program program = new Program();

        int size = ctx.expr().size();

        // ✓ Créer un registre pour stocker l'adresse de début du tableau
        int tableAddrReg = newRegister();

        //  Sauvegarder l'adresse actuelle du heap (TP) dans tableAddrReg
        program.addInstruction(new UALi(UALi.Op.ADD, tableAddrReg, this.TP, 0));

        //  Créer un registre pour la longueur
        int lengthReg = newRegister();
        program.addInstructions(this.setRegisterTo(lengthReg, size));

        //  Stocker la longueur à l'adresse TP
        program.addInstruction(new Mem(Mem.Op.ST, lengthReg, this.TP));

        //  Avancer TP de 1 pour pointer sur la première case de données
        program.addInstruction(new UALi(UALi.Op.ADD, this.TP, this.TP, 1));

        //  Boucle sur toutes les expressions pour remplir le tableau
        for (grammarTCLParser.ExprContext exprCtx : ctx.expr()) {
            program.addInstructions(visit(exprCtx));
            int exprReg = regCount - 1;

            // Stocker la valeur à l'adresse TP
            program.addInstruction(new Mem(Mem.Op.ST, exprReg, this.TP));

            // Avancer TP de 1
            program.addInstruction(new UALi(UALi.Op.ADD, this.TP, this.TP, 1));
        }
        //  copier tableAddrReg dans un nouveau registre final
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