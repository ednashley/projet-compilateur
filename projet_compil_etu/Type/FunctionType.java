package Type;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;

public class FunctionType extends Type {
    private Type returnType;
    private ArrayList<Type> argsTypes;
    
    /**
     * Constructeur
     * @param returnType type de retour
     * @param argsTypes liste des types des arguments
     */
    public FunctionType(Type returnType, ArrayList<Type> argsTypes) {
        this.returnType = returnType;
        this.argsTypes = argsTypes;
    }

    /**
     * Getter du type de retour
     * @return type de retour
     */
    public Type getReturnType() {
        return returnType;
    }

    /**
     * Getter du type du i-eme argument
     * @param i entier
     * @return type du i-eme argument
     */
    public Type getArgsType(int i) {
        return argsTypes.get(i);
    }

    /**
     * Getter du nombre d'arguments
     * @return nombre d'arguments
     */
    public int getNbArgs() {
        return argsTypes.size();
    }

    /**
     * Unification :
     * Unifie les types de retour, puis unifie séquentiellement chaque paire de types d'arguments.
     * La substitution résultante de chaque unification est appliquée aux types restants avant l'étape suivante (composition).
     * @param t type à unifier
     * @return la liste des substitutions à effectuer (null si pas unifiable)
     */
    @Override
    public Map<UnknownType, Type> unify(Type t) {
        // Rempli
        if (!(t instanceof FunctionType)) return null;

        FunctionType other = (FunctionType) t;
        if (this.argsTypes.size() != other.argsTypes.size()) return null;

        Map<UnknownType, Type> subs = new HashMap<>();

        // 1. Unification du type de retour
        Map<UnknownType, Type> subReturn = this.returnType.unify(other.returnType);
        if (subReturn == null) return null;
        subs.putAll(subReturn); // Ajout des substitutions trouvées

        // 2. Unification séquentielle des arguments (avec composition)
        for (int i = 0; i < argsTypes.size(); i++) {

            // ÉTAPE CRITIQUE : Appliquer toutes les substitutions courantes (subs) aux types AVANT l'unification!
            Type t1 = this.argsTypes.get(i).substituteAll(subs);
            Type t2 = other.argsTypes.get(i).substituteAll(subs);

            Map<UnknownType, Type> subArg = t1.unify(t2);
            if (subArg == null) return null;

            // COMPOSITION DES SUBSTITUTIONS
            // Créer un nouvel ensemble de substitutions en composant subs avec subArg.

            Map<UnknownType, Type> composedSubs = new HashMap<>();

            // Appliquer les nouvelles substitutions (subArg) aux valeurs des substitutions existantes (subs)
            for (Map.Entry<UnknownType, Type> entry : subs.entrySet()) {
                composedSubs.put(entry.getKey(), entry.getValue().substituteAll(subArg));
            }

            // Ajouter les nouvelles substitutions (subArg), elles priment sur les anciennes
            composedSubs.putAll(subArg);

            subs = composedSubs; // Mise à jour de la map globale
        }
        return subs;
    }



    /**
     * Substitution :
     * Applique la substitution récursivement au type de retour et à tous les types d'arguments.
     * @param v type variable à substituer
     * @param t type par lequel remplacer v
     * @return Type obtenu en remplaçant v par t
     */
    @Override
    public Type substitute(UnknownType v, Type t) {
        // Rempli
        // 1. Substitution du type de retour (récursif)
        Type newReturnType = this.returnType.substitute(v, t);

        // 2. Substitution des types d'arguments (récursif)
        ArrayList<Type> newArgsTypes = new ArrayList<>();
        boolean changed = false; // Flag pour optimiser

        for (Type argType : argsTypes) {
            Type newArgType = argType.substitute(v, t);
            newArgsTypes.add(newArgType);

            if (newArgType != argType) {
                changed = true;
            }
        }

        // Si des changements ont eu lieu dans le type de retour OU les arguments
        if (newReturnType != this.returnType || changed) {
            // Retourne une nouvelle instance de FunctionType avec les types mis à jour.
            return new FunctionType(newReturnType, newArgsTypes);
        }

        // Sinon, retourne l'instance actuelle (immuabilité)
        return this;
    }

    /**
     * Test d'occurrence :
     * Vérifie si la variable de type 'v' est contenue récursivement dans le type de retour
     * ou dans un des types d'arguments.
     * @param v type variable
     * @return boolean
     */
    @Override
    public boolean contains(UnknownType v) {
        // Rempli
        // Vérifier le type de retour
        if (this.returnType.contains(v)) {
            return true;
        }
        // Vérifier les types d'arguments
        for (Type argType : argsTypes) {
            if (argType.contains(v)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Test d'égalité :
     * Deux FunctionType sont égaux si :
     * 1. Ils ont le même nombre d'arguments.
     * 2. Leurs types de retour sont égaux.
     * 3. Tous leurs types d'arguments sont égaux dans l'ordre.
     * @param t Object
     * @return boolean
     */
    @Override
    public boolean equals(Object t) {
        // Rempli
        if (this == t) return true;
        if (!(t instanceof FunctionType)) return false;

        FunctionType other = (FunctionType) t;

        // 1. Vérifier le nombre d'arguments
        if (this.argsTypes.size() != other.argsTypes.size()) {
            return false;
        }

        // 2. Vérifier l'égalité du type de retour
        if (!this.returnType.equals(other.returnType)) {
            return false;
        }
        // 3. Vérifier l'égalité de tous les types d'arguments
        for (int i = 0; i < argsTypes.size(); i++) {
            if (!this.argsTypes.get(i).equals(other.argsTypes.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Représentation en chaîne de caractères.
     * Format : (Arg1, Arg2, ...) -> ReturnType
     * @return String
     */
    @Override
    public String toString() {
        // Rempli
        StringBuilder sb = new StringBuilder();
        // Types des arguments
        sb.append("(");
        for (int i = 0; i < argsTypes.size(); i++) {
            sb.append(argsTypes.get(i).toString());
            if (i < argsTypes.size() - 1) {
                sb.append(", ");
            }
        }
        sb.append(")");
        // Type de retour
        sb.append(" -> ");
        sb.append(returnType.toString());
        return sb.toString();
    }

}
