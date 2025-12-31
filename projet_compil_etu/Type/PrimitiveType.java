package Type;
import java.util.Map;
import java.util.HashMap;

public  class PrimitiveType extends Type {
    private Type.Base type; 
    
    /**
     * Constructeur
     * @param type type de base
     */
    public PrimitiveType(Type.Base type) {
        this.type = type;
    }

    /**
     * Getter du type
     * @return type
     */
    public Type.Base getType() {
        return type;
    }


    /**
     * Unification :
     * Un PrimitiveType 'this' est unifiable avec un type 't' si et seulement si :
     * 1. 't' est aussi un PrimitiveType et a le même type de base
     * 2. 't' est un UnknownType, on retourne alors la substitution t -> this
     * @param t type à unifier
     * @return la liste des substitutions à effectuer (vide ou {t -> this}) ou null si pas unifiable.
     */
    @Override
    public Map<UnknownType, Type> unify(Type t) {
        // Rempli
        if (t instanceof PrimitiveType) {
            PrimitiveType other = (PrimitiveType) t;
            if (this.type == other.type) {
                return new HashMap<>();
            } else {
                return null;
            }
        } else if (t instanceof UnknownType) {
            // CLÉ : Un PrimitiveType (this) s'unifie avec un UnknownType (t) en déléguant et en RETOURNANT le résultat.
            return t.unify(this);
        }
        return null;
    }


    /**
     * Substitution :
     * Un PrimitiveType ne contient pas de variables de type à substituer.
     * Il est donc retourné inchangé.
     */
    @Override
    public Type substitute(UnknownType v, Type t) {
        // Rempli
        return this;
    }

    /**
     * Test d'occurrence :
     * Un PrimitiveType ne contient pas de variable de type
     */
    @Override
    public boolean contains(UnknownType v) {
        // Rempli
        return false;
    }


    /**
     * Test d'égalité :
     * Deux PrimitiveType sont égaux si et seulement s'ils sont du même type de base.
     */
    @Override
    public boolean equals(Object t) {
        // Rempli
        if (this == t) return true;
        if (!(t instanceof PrimitiveType)) return false;

        PrimitiveType other = (PrimitiveType) t;
        return this.type == other.type;
    }

    /**
     * Représentation en chaîne de caractères.
     */
    @Override
    public String toString() {
        // Rempli
        return this.type.toString(); // Retourne 'int' ou 'bool'
    }
}
