package Type;
import java.util.Map;

public class ArrayType extends Type{
    private Type tabType;
    
    /**
     * Constructeur
     * @param t type des éléments du tableau
     */
    public ArrayType(Type t) {
        this.tabType = t;
    }

    /**
     * Getter du type des éléments du tableau
     * @return type des éléments du tableau
     */
    public Type getTabType() {
       return tabType;
    }
    
    /**
     * Unification :
     * Un ArrayType 'this' est unifiable avec 't' si et seulement si :
     * 1. 't' est aussi un ArrayType et le type des éléments (tabType) est unifiable récursivement.
     * 2. Si 't' est un UnknownType , on délègue à t.unify(this).
     * @param t type à unifier
     * @return la liste des substitutions à effectuer (combinée avec celles de l'élément) ou null.
     */
    @Override
    public Map<UnknownType, Type> unify(Type t) {
        // Rempli
        if (t instanceof ArrayType) {
            // ...
            ArrayType other = (ArrayType) t;
            return this.tabType.unify(other.tabType);
        } else if (t instanceof UnknownType) {
            // Délègue à UnknownType, et RETOURNE le résultat
            return t.unify(this);
        }
        return null; // Échec
    }

    /**
     * Substitution :
     * Applique la substitution récursivement au type des éléments.
     */
    @Override
    public Type substitute(UnknownType v, Type t) {
        // Rempli
        if (this.equals(t)) return t;

        // Substitution récursive du type contenu dans le tableau
        Type newTabType = this.tabType.substitute(v, t);

        if (newTabType == this.tabType) {
            return this;
        }
        return new ArrayType(newTabType);
    }
    
    /**
     * Test d'occurrence :
     * Vérifie si la variable de type 'v' est contenue récursivement dans le type des éléments.
     */
    @Override
    public boolean contains(UnknownType v) {
        // Rempli
        return this.tabType.contains(v);
    }

    /**
     * Test d'égalité :
     * Deux ArrayType sont égaux si et seulement si leurs types d'éléments sont égaux.
     */
    @Override
    public boolean equals(Object t) {
        // Rempli
        if (this == t) return true;
        if (!(t instanceof ArrayType)) return false;

        ArrayType other = (ArrayType) t;
        // L'égalité dépend de l'égalité récursive de leurs types d'éléments.
        return this.tabType.equals(other.tabType);
    }

    /**
     * Représentation en chaîne de caractères.
     * Exemple : "int[]" ou "bool[][]"
     */
    @Override
    public String toString() {
        // Rempli
        return this.tabType.toString() + "[]";
    }

    
}
