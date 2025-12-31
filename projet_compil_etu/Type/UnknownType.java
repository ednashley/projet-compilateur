package Type;
import java.util.Map;
import java.util.HashMap;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

public class UnknownType extends Type {
    private String varName;
    private int varIndex;
    private static int newVariableCounter = 0;

    /**
     * Constructeur sans nom
     */
    public UnknownType(){
        this.varIndex = newVariableCounter++;
        this.varName = "#";
    }

    /**
     * Constructeur à partir d'un nom de variable et un numéro
     * @param s nom de variable
     * @param n numéro de la variable
     */
    public UnknownType(String s, int n)  {
        this.varName = s;        
        this.varIndex = n;
    }

    /**
     * Constructeur à partir d'un ParseTree (standardisation du nom de variable)
     * @param ctx ParseTree
     */
    public UnknownType(ParseTree ctx) {
        this.varName = ctx.getText();
        if (ctx instanceof TerminalNode) {
            this.varIndex = ((TerminalNode)ctx).getSymbol().getStartIndex();
        } else {
            if (ctx instanceof ParserRuleContext) {
                this.varIndex = ((ParserRuleContext)ctx).getStart().getStartIndex();
            }
            else {
                throw new Error("Illegal UnknownType construction");
            }
        }
    }

    /**
     * Getter du nom de variable de type
     * @return variable de type
     */
    public String getVarName() {
        return varName;
    }

    /**
     * Getter du numéro de variable de type
     * @return numéro de variable de type
     */
    public int getVarIndex() {
        return varIndex;
    }

    /**
     * Setter du numéro de variable de type
     * @param n numéro de variable de type
     */
    public void setVarIndex(int n) {
        this.varIndex = n;
    }

    @Override
    public Map<UnknownType, Type> unify(Type t) {
        // Rempli
        if (t.equals(this)) {
            return new HashMap<>();
        }

        // ÉTAPE CRITIQUE : Test d'occurrence (pour éviter alpha -> Array<alpha>)
        if (t.contains(this)) {
            // Si 't' contient la variable de type 'this', échec d'unification.
            return null;
        }

        // Création de la substitution : {this -> t}
        Map<UnknownType, Type> subs = new HashMap<>();
        subs.put(this, t);

        return subs;
    }

    /**
     * Substitution :
     * Si cette variable de type est celle à substituer (v), on retourne le type de remplacement (t).
     * Sinon, on retourne 'this' inchangé.
     */
    @Override
    public Type substitute(UnknownType v, Type t) {
        // Rempli
        if (this.equals(v)) {
            return t; // C'est la variable de type, on la remplace.
        }
        return this; // Ce n'est pas la variable à remplacer.
    }

    /**
     * Test d'occurrence :
     * Une variable de type 'this' contient 'v' si et seulement si 'this' et 'v' sont la même variable.
     */
    @Override
    public boolean contains(UnknownType v) {
        // Rempli
        return this.equals(v);
    }

    /**
     * Test d'égalité :
     * Deux UnknownType sont égaux si et seulement si ils ont le même numéro d'index,
     */
    @Override
    public boolean equals(Object t) {
        // Rempli
        if (this == t) return true;
        if (!(t instanceof UnknownType)) return false;

        UnknownType other = (UnknownType) t;
        // On compare l'index de la variable de type.
        return this.varIndex == other.varIndex;
    }

    /**
     * Représentation en chaîne de caractères.
     * Utilise le nom et l'index pour garantir l'unicité dans la HashMap.
     */
    @Override
    public String toString() {
        // Rempli
        if (varName.equals("#")) {
            return "alpha" + this.varIndex; // Notation standard pour les variables de type
        }
        return this.varName + "_" + this.varIndex;
    }

    

}
