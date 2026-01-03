import java.util.*;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.AbstractParseTreeVisitor;

import Type.Type;
import Type.UnknownType;
import Type.PrimitiveType;
import Type.ArrayType;
import Type.FunctionType;
import java.util.ArrayList;

public class TyperVisitor extends AbstractParseTreeVisitor<Type> implements grammarTCLVisitor<Type> {

    private Map<UnknownType,Type> types = new HashMap<UnknownType,Type>();

    // Environnement : Pile de Map pour gérer les étapes (Nom variable/fonction -> Type)
    private Stack<Map<String, Type>> env = new Stack<>();

    // Stack pour stocker le type de retour attendu de la fonction en cours
    private Stack<Type> returnTypeStack = new Stack<>();

    private final PrimitiveType INT_TYPE = new PrimitiveType(Type.Base.INT);
    private final PrimitiveType BOOL_TYPE = new PrimitiveType(Type.Base.BOOL);
    private final Type VOID_TYPE = null; // Type pour les instructions

    public TyperVisitor() {
        // Initialisation globale
        env.push(new HashMap<>());
    }

    public Map<UnknownType, Type> getTypes() {
        return types;
    }

    /**
     * Empile l'étape actuelle
     */
    private void pushStage() {
        env.push(new HashMap<>());
    }

    /**
     * Dépile l'étape actuelle
     */
    private void popStage() {
        env.pop();
    }


    /**
     * Enregistre une variable/fonction dans l'étape actuelle
     * @param name Nom de la variable
     * @param type Type de la variable
     */
    private void define(String name, Type type) {
        if (env.peek().containsKey(name)) {
            throw new RuntimeException("Type Error: Redéclaration de " + name);
        }
        env.peek().put(name, type);
    }

    /**
     * Recherche le type d'un identifiant en remontant la pile
     * @param name Nom de la variable
     * @return
     */
    private Type lookup(String name) {
        for (int i = env.size() - 1; i >= 0; i--) {
            if (env.get(i).containsKey(name)) {
                return env.get(i).get(name);
            }
        }
        throw new RuntimeException("Type Error: Variable ou fonction non déclarée " + name);
    }

    /**
     * Applique une substitution à l'ensemble de l'environnement et à la map global
     * @param subst La substitution à répandre
     */
    private void applySubstitution(Map<UnknownType, Type> subst) {
        if (subst == null || subst.isEmpty()) {
            return;
        }
        // 1. Appliquer la substitution à toutes les variables/fonctions dans toutes les étapes
        for (Map<String, Type> scope : env) {
            for (Map.Entry<String, Type> entry : scope.entrySet()) {
                entry.setValue(entry.getValue().substituteAll(subst));
            }
        }
        // 2. Appliquer la substitution à la map globale 'types' (composition)
        Map<UnknownType, Type> newGlobalTypes = new HashMap<>();
        for (Map.Entry<UnknownType, Type> entry : this.types.entrySet()) {
            newGlobalTypes.put(entry.getKey(), entry.getValue().substituteAll(subst));
        }
        this.types.putAll(newGlobalTypes);
        // 3. Ajouter la nouvelle substitution à la map globale
        this.types.putAll(subst);
    }

    /**
     * Tente d'unifier 2 types; si ils sont unifiables, elle appelle
     * applySubstitution pour l'appliquer partout
     * @param t1 1er Type
     * @param t2 2e Type
     * @param ctx Contexte
     */
    private void unifyAndApply(Type t1, Type t2, ParserRuleContext ctx) {
        Map<UnknownType, Type> subst = t1.unify(t2);
        if (subst == null) {
            String msg = String.format("Type Error à la ligne %d : Impossible d'unifier %s et %s.",
                    ctx.start.getLine(), t1.toString(), t2.toString());
            throw new RuntimeException(msg);
        }
        applySubstitution(subst);
    }

    /**
     * Gère les négations (NOT)
     * @param ctx the parse tree
     * @return le type BOOL
     */
    @Override
    public Type visitNegation(grammarTCLParser.NegationContext ctx) {
        Type t = visit(ctx.expr());
        unifyAndApply(t, BOOL_TYPE, ctx);
        return BOOL_TYPE;
    }

    /**
     * Gère les comparaisons (<; <=; >; >=)
     * @param ctx the parse tree
     * @return le type BOOL
     */
    @Override
    public Type visitComparison(grammarTCLParser.ComparisonContext ctx) {
        Type t1 = visit(ctx.expr(0));
        Type t2 = visit(ctx.expr(1));

        unifyAndApply(t1, INT_TYPE, ctx);
        unifyAndApply(t2, INT_TYPE, ctx);

        return BOOL_TYPE.substituteAll(types);
    }

    /**
     * Gère les expressions contenant OR
     * @param ctx the parse tree
     * @return le type BOOL
     */
    @Override
    public Type visitOr(grammarTCLParser.OrContext ctx) {
        Type t1 = visit(ctx.expr(0));
        Type t2 = visit(ctx.expr(1));
        unifyAndApply(t1, BOOL_TYPE, ctx);
        unifyAndApply(t2, BOOL_TYPE, ctx);
        return BOOL_TYPE;
    }

    /**
     * Gère les opposés de nombre
     * @param ctx the parse tree
     * @return le type INT
     */
    @Override
    public Type visitOpposite(grammarTCLParser.OppositeContext ctx) {
        Type t = visit(ctx.expr());
        unifyAndApply(t, INT_TYPE, ctx);
        return INT_TYPE;
    }


    /**
     * Gère les types entiers
     * @param ctx the parse tree
     * @return le type INT
     */
    @Override
    public Type visitInteger(grammarTCLParser.IntegerContext ctx) {
        return INT_TYPE;
    }

    /**
     * Accès à un élément du tableau
     * Vérifie que l'index est un INT
     * Unifie le type du tableau avec un tableau d'UnknownType
     * @param ctx the parse tree
     * @return le type Unknown après substitution
     */
    @Override
    public Type visitTab_access(grammarTCLParser.Tab_accessContext ctx) {
        Type tVar = visit(ctx.expr(0));
        Type tIndex = visit(ctx.expr(1));

        unifyAndApply(tIndex, INT_TYPE, ctx);

        UnknownType tElem = new UnknownType();
        ArrayType tExpectedArray = new ArrayType(tElem);

        unifyAndApply(tVar, tExpectedArray, ctx);

        return tElem.substituteAll(this.types);
    }

    /**
     * Détermine le type du résultat d'une indexation
     * @param ctx the parse tree
     * @return le type de l'élément contenu dans le tableau
     */
    @Override
    public Type visitBrackets(grammarTCLParser.BracketsContext ctx) {
        // Parenthèses: retourner le type de l'expression interne
        Type inner = visit(ctx.expr());
        return inner.substituteAll(this.types);
    }

    /**
     * Appel de fonction
     * Unifie le type de la fonction avec le type de la fonction à appeler
     * @param ctx the parse tree
     * @return
     */
    @Override
    public Type visitCall(grammarTCLParser.CallContext ctx) {
        String fctName = ctx.VAR().getText();
        Type tFct = lookup(fctName);

        if (!(tFct instanceof FunctionType)) {
            throw new RuntimeException("Type Error: L'identifiant " + fctName + " n'est pas une fonction.");
        }

        FunctionType signature = (FunctionType) instantiateType(tFct, new HashMap<UnknownType,UnknownType>());

        List<grammarTCLParser.ExprContext> callArgs = ctx.expr();

        if (signature.getNbArgs() != callArgs.size()) {
            throw new RuntimeException("Type Error: Nombre d'arguments incorrect pour " + fctName);
        }

        for (int i = 0; i < callArgs.size(); i++) {
            Type expectedArgType = signature.getArgsType(i);
            // callArgs.get(i) est un ExprContext
            Type actualArgType = visit(callArgs.get(i));

            unifyAndApply(expectedArgType, actualArgType, ctx);
        }

        UnknownType tResult = new UnknownType();
        unifyAndApply(signature.getReturnType(), tResult, ctx);

        return tResult.substituteAll(this.types);
    }

    /**
     * Instancie un type en remplaçant chaque occurrence de UnknownType par une nouvelle instance de UnknownType.
     * Utilise un map pour assurer la cohérence des remplacements au sein d'une même instance.
     */
    private Type instantiateType(Type t, Map<UnknownType, UnknownType> mapping) {
        if (t instanceof UnknownType) {
            UnknownType u = (UnknownType) t;
            if (mapping.containsKey(u)) return mapping.get(u);
            UnknownType fresh = new UnknownType();
            mapping.put(u, fresh);
            return fresh;
        }
        if (t instanceof PrimitiveType) return t;
        if (t instanceof ArrayType) {
            ArrayType at = (ArrayType) t;
            return new ArrayType(instantiateType(at.getTabType(), mapping));
        }
        if (t instanceof FunctionType) {
            FunctionType ft = (FunctionType) t;
            Type newRet = instantiateType(ft.getReturnType(), mapping);
            ArrayList<Type> newArgs = new ArrayList<>();
            for (int i = 0; i < ft.getNbArgs(); i++) {
                newArgs.add(instantiateType(ft.getArgsType(i), mapping));
            }
            return new FunctionType(newRet, newArgs);
        }
        return t;
    }

    /**
     * Gère les booléens
     * @param ctx the parse tree
     * @return le type BOOL
     */
    @Override
    public Type visitBoolean(grammarTCLParser.BooleanContext ctx) {
        return BOOL_TYPE;
    }

    /**
     * Gère les expressions contenant AND
     * @param ctx the parse tree
     * @return le type BOOL
     */
    @Override
    public Type visitAnd(grammarTCLParser.AndContext ctx) {
        Type t1 = visit(ctx.expr(0));
        Type t2 = visit(ctx.expr(1));
        unifyAndApply(t1, BOOL_TYPE, ctx);
        unifyAndApply(t2, BOOL_TYPE, ctx);
        return BOOL_TYPE;
    }

    /**
     * Gère l'utilisation d'une variable
     * @param ctx the parse tree
     * @return le type de la variable obtenu par lookup
     */
    @Override
    public Type visitVariable(grammarTCLParser.VariableContext ctx) {
        String id = ctx.VAR().getText();
        Type variableType = lookup(id);
        return variableType.substituteAll(types);
    }

    /**
     * Gère les multiplications
     * @param ctx the parse tree
     * @return le type INT si possible
     */
    @Override
    public Type visitMultiplication(grammarTCLParser.MultiplicationContext ctx) {
        Type t1 = visit(ctx.expr(0));
        Type t2 = visit(ctx.expr(1));

        unifyAndApply(t1, INT_TYPE, ctx);
        unifyAndApply(t2, INT_TYPE, ctx);

        return INT_TYPE;
    }

    /**
     * Gère les égalités
     * @param ctx the parse tree
     * @return le type BOOL
     */
    @Override
    public Type visitEquality(grammarTCLParser.EqualityContext ctx) {
        Type t1 = visit(ctx.expr(0));
        Type t2 = visit(ctx.expr(1));

        unifyAndApply(t1, t2, ctx);

        return BOOL_TYPE;
    }

    /**
     * Initialise un tableau
     * Vérifie que la taille du tableau est un entier
     * @param ctx the parse tree
     * @return le type ArrayType(type des valeurs du tableau)
     */
    @Override
    public Type visitTab_initialization(grammarTCLParser.Tab_initializationContext ctx) {
        if (ctx.expr().isEmpty()) {
            return new ArrayType(new UnknownType());
        }
        Type tElem = visit(ctx.expr(0));

        // Vérifie que tous les autres éléments s'unifient avec le premier
        for (int i = 1; i < ctx.expr().size(); i++) {
            Type tNext = visit(ctx.expr(i));
            unifyAndApply(tElem, tNext, ctx);
        }

        return new ArrayType(tElem.substituteAll(this.types));
    }

    /**
     * Gère les additions
     * @param ctx the parse tree
     * @return le type INT si possible
     */
    @Override
    public Type visitAddition(grammarTCLParser.AdditionContext ctx) {
        Type t1 = visit(ctx.expr(0));
        Type t2 = visit(ctx.expr(1));

        unifyAndApply(t1, INT_TYPE, ctx);
        unifyAndApply(t2, INT_TYPE, ctx);

        return INT_TYPE.substituteAll(types);
    }

    /**
     * Gère la déclaration d'un type
     * @param ctx the parse tree
     * @return le type du fils
     */
    @Override
    public Type visitBase_type(grammarTCLParser.Base_typeContext ctx) {
        String typeKeyword = ctx.getChild(0).getText();
        if (typeKeyword.equals("int")) {
            return INT_TYPE;
        }
        if (typeKeyword.equals("bool")) {
            return BOOL_TYPE;
        }
        if (typeKeyword.equals("auto")) {
            return new UnknownType();
        }
        throw new RuntimeException("Type Error: Type de base inconnu: " + typeKeyword);
    }

    /**
     * Gère la déclaration d'un type tableau
     * @param ctx the parse tree
     * @return un ArrayType dont le type a été déterminé par la méthode visit
     */
    @Override
    public Type visitTab_type(grammarTCLParser.Tab_typeContext ctx) {
        Type elementType = visit(ctx.type());
        return new ArrayType(elementType);
    }

    /**
     * Déclaration de variable
     * Unifie le type déclaré avec le type de l'expression
     * @param ctx the parse tree
     * @return null (VOID_TYPE)
     */
    @Override
    public Type visitDeclaration(grammarTCLParser.DeclarationContext ctx) {
        String varName = ctx.VAR().getText();
        Type tDeclared = visit(ctx.type());

        // Utilisation de la seule méthode ctx.expr() disponible
        if (ctx.expr() != null) {
            Type tExpr = visit(ctx.expr());
            unifyAndApply(tDeclared, tExpr, ctx);
        }

        Type finalType = tDeclared.substituteAll(this.types);
        define(varName, finalType);

        return VOID_TYPE;
    }

    /**
     *
     * @param ctx the parse tree
     * @return
     */
    @Override
    public Type visitPrint(grammarTCLParser.PrintContext ctx) {
        // Le token à l'intérieur de print(...) est un VAR selon la grammaire
        String varName = ctx.VAR().getText();
        Type tExpr = lookup(varName);
        return VOID_TYPE;
    }

    /**
     * Unifie le type du fils gauche avec celui du fils droit
     * @param ctx the parse tree
     * @return VOID_TYPE
     */
    @Override
    public Type visitAssignment(grammarTCLParser.AssignmentContext ctx) {
        String id = ctx.VAR().getText();
        Type variableType = lookup(id);

        // Dans la grammaire, ctx.expr() contient d'abord les expressions d'index
        // (si présentes), puis l'expression de droite (la valeur assignée) en dernier.
        List<grammarTCLParser.ExprContext> exprs = ctx.expr();
        if (exprs.size() == 0) {
            throw new RuntimeException("Type Error: Assignation sans expression.");
        }

        // Expression de droite
        Type expressionType = visit(exprs.get(exprs.size() - 1));

        // Si il y a des indices, vérifier qu'ils sont des INT et descendre dans le tableau
        for (int i = 0; i < exprs.size() - 1; i++) {
            Type indexType = visit(exprs.get(i));
            unifyAndApply(indexType, INT_TYPE, ctx);

            // On s'attend à ce que variableType soit un ArrayType(...)
            UnknownType elem = new UnknownType();
            ArrayType expectedArray = new ArrayType(elem);
            unifyAndApply(variableType, expectedArray, ctx);

            // Après unification, appliquer les substitutions et continuer avec le type élément
            variableType = elem.substituteAll(this.types);
        }

        // Enfin unifier le type ciblé (variableType) avec l'expression assignée
        unifyAndApply(variableType, expressionType, ctx);

        return VOID_TYPE;
    }

    /**
     * Visite les instructions dans les blocs
     * @param ctx the parse tree
     * @return VOID_TYPE
     */
    @Override
    public Type visitBlock(grammarTCLParser.BlockContext ctx) {
        pushStage();
        for (grammarTCLParser.InstrContext instr : ctx.instr()) {
            visit(instr);
        }
        popStage();
        return VOID_TYPE;
    }

    /**
     * Visite que la condition est un BOOL_TYPE
     * Visite le corps de la condition
     * @param ctx the parse tree
     * @return VOID_TYPE
     */
    @Override
    public Type visitIf(grammarTCLParser.IfContext ctx) {
        Type conditionType = visit(ctx.expr());
        unifyAndApply(conditionType, BOOL_TYPE, ctx); // La condition doit être BOOL

        visit(ctx.instr(0));
        if (ctx.instr().size() > 1) {
            visit(ctx.instr(1));
        }

        return VOID_TYPE;
    }

    /**
     * Visite que la condition est un BOOL_TYPE
     * Visite le corps de la condition
     * @param ctx the parse tree
     * @return VOID_TYPE
     */
    @Override
    public Type visitWhile(grammarTCLParser.WhileContext ctx) {
        Type conditionType = visit(ctx.expr());
        unifyAndApply(conditionType, BOOL_TYPE, ctx);

        visit(ctx.instr());

        return VOID_TYPE;
    }

    /**
     * Vérifie que la condition est BOOL_TYPE
     * @param ctx the parse tree
     * @return VOID_TYPE
     */
    @Override
    public Type visitFor(grammarTCLParser.ForContext ctx) {
        pushStage();
        visit(ctx.instr(0)); // Initialisation
        Type tCond = visit(ctx.expr()); // Condition
        unifyAndApply(tCond, BOOL_TYPE, ctx);
        visit(ctx.instr(2)); // Corps de la boucle
        visit(ctx.instr(1)); // Itération (après exécution du corps)
        popStage();
        return VOID_TYPE;
    }

    /**
     * Instruction return
     * Unifie le type de l'expression retournée avec le type attendu
     * @param ctx the parse tree
     * @return VOID_TYPE
     */
    @Override
    public Type visitReturn(grammarTCLParser.ReturnContext ctx) {
        if (returnTypeStack.isEmpty()) {
            throw new RuntimeException("Type Error: Instruction 'return' en dehors d'une fonction.");
        }
        Type tExpected = returnTypeStack.peek();

        Type tReturned = visit(ctx.expr());
        unifyAndApply(tExpected, tReturned, ctx);

        return VOID_TYPE;
    }

    /**
     * Fonction READ
     * @param ctx the parse tree
     * @return INT_TYPE
     */
    @Override
    public Type visitCore_fct(grammarTCLParser.Core_fctContext ctx) {

        for (grammarTCLParser.InstrContext instructionCtx : ctx.instr()) {
            visit(instructionCtx);
        }
        return VOID_TYPE; // Un bloc ne retourne rien
    }

    /**
     * Déclaration de fonction
     * Construit un type fonction avec les types d'arguments et de retour
     * Empile le type de retour
     * Visite le corps
     * @param ctx the parse tree
     * @return VOID_TYPE
     */
    @Override
    public Type visitDecl_fct(grammarTCLParser.Decl_fctContext ctx) {
        // 1. Récupération du type de retour (ctx.type(0))
        Type returnType = visit(ctx.type(0));

        // 2. Récupération des types des arguments (index 1 et suivants)
        ArrayList<Type> argsTypes = new ArrayList<>();
        for (int i = 1; i < ctx.type().size(); i++) {
            argsTypes.add(visit(ctx.type(i)));
        }

        // 3. Créer et enregistrer la signature de la fonction (TypeFunction)
        FunctionType fctType = new FunctionType(returnType, argsTypes);
        String fctName = ctx.VAR(0).getText();
        define(fctName, fctType);

        pushStage();
        returnTypeStack.push(returnType); // Type de retour attendu pour les 'return'

        // Enregistrement des paramètres dans le scope local
        for (int i = 0; i < ctx.VAR().size() - 1; i++) {
            String paramName = ctx.VAR(i+1).getText(); // Le premier ID est le nom de la fct
            define(paramName, argsTypes.get(i));
        }

        visit(ctx.core_fct()); // Visiter le corps (qui gère son propre push/popStage d'après votre implémentation)

        // Nettoyage
        returnTypeStack.pop();
        popStage();

        return fctType;
    }

    /**
     * Fonction principale
     * Empile INT_TYPE comme type de retour attendu
     * @param ctx the parse tree
     * @return VOID_TYPE
     */
    @Override
    public Type visitMain(grammarTCLParser.MainContext ctx) {
        // 1. Enregistrement de la fonction main (dans le scope global, env est déjà initialisé)
        ArrayList<Type> mainArgs = new ArrayList<>();
        FunctionType mainType = new FunctionType(INT_TYPE, mainArgs);
        define("main", mainType);

        // 2. Préparation du type de retour attendu (main retourne INT_TYPE)
        returnTypeStack.push(INT_TYPE);

        for (grammarTCLParser.Decl_fctContext fctCtx : ctx.decl_fct()) {
            visit(fctCtx);
        }

        //  Visiter le corps de main (qui appellera visitCore_fct)
        visit(ctx.core_fct());

        //  Nettoyage
        returnTypeStack.pop();

        return VOID_TYPE;
    }

    public Map<String, Type> getEnvironment() {

        Map<String, Type> result = new HashMap<>();

        if (!env.isEmpty()) {
            result.putAll(env.get(0));
        }
        return result;
    }
}
